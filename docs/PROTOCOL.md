# Soul2Soul 线上协议参考（PROTOCOL）

> 本文档描述**当前实现**的真实协议，以代码为准逐字段核对。
> docs/SPEC.md 的 §2/§3 是早期设计稿，部分已过时（见文中勘误指针）。

## 1. 传输层

- 信令：WebSocket，`ws://<host>:8080`（生产经反向代理为 `wss://soul.lumi666.cloud`）。单条消息上限 256KB（`MAX_MESSAGE_BYTES`，SDP offer 约 50KB）。
- 媒体：WebRTC（DTLS-SRTP），由信令中转 SDP/ICE 建立。
- 所有信令消息均为 JSON 对象，路由字段为 `type`（字符串，服务端截取前 32 字符）。非对象/数组/`null` 一律静默丢弃。

## 2. WebSocket 信令目录

### 2.1 客户端 → 服务端

| type | 载荷 | 前置条件 | 说明 |
|---|---|---|---|
| `hello` | `deviceId`(≤64字符), `token`(可选) | 连接后第一条 | 注册设备。已配对设备**必须**携带正确 token，否则回 `{type:"error",reason:"bad_token"}` 并以 4001 关闭连接（防知 deviceId 即冒充）。 |
| `pair.request` | — | 已 hello | 申请 6 位配对码。每设备限速 5s/次（超限回 `pair.failed:too_fast`）；申请新码即作废旧码。 |
| `pair.enter` | `code` | 已 hello | 输入对方配对码。每连接最多尝试 5 次（防爆破，超限回 `too_many_attempts`）；码过期/不存在回 `code_invalid`；输自己的码回 `self_pair`。扫码配对只是客户端把二维码里的 6 位码喂给这条通道，服务器无特判。 |
| `pair.recall` | — | 已 hello + 未配对 | 老搭档找回：服务端校验 30 天内配对历史（`history.json`），在线时给对方下发 `pair.recall_ask`。无历史/限速（10s/次）回 `pair.recall_failed`。 |
| `pair.recall_ok` | `accept`(bool) | 已 hello + 未配对 | 被召回方答复：true 且双方仍未配对 → 双向 `paired`（新 token）；false → 发起端收 `pair.recall_failed:declined`；超 60s 未答自动 `expired`。 |
| `pair.shake` | — | 已 hello + 未配对 | 本机摇一摇判定通过后上报（客户端 ShakeDetector + 服务端 2s 节流）。"对方有找回请求正等你" 或 "3s 内双方都摇过" 才直绑；单摇只进等待，永不单方绑定。 |
| `invite` | — | 已鉴权 | 呼叫对端。限速 2s/次。对端不在线回 `peer.offline`。 |
| `accept` | — | 已鉴权 | 接听。 |
| `decline` | — | 已鉴权 | 拒接。 |
| `cancel` | — | 已鉴权 | 主叫在对方接听前撤呼。 |
| `bye` | — | 已鉴权 | 挂断，原样转发给对端。 |
| `sdp` | `sdp:{type, sdp}` | 已鉴权 | 原样转发给对端（日志省略正文）。 |
| `ice` | `candidate:{...}` | 已鉴权 | 原样转发给对端（日志省略正文）。 |
| `unpair` | — | 已鉴权 | 解除配对：删除双向关系并**双向清空留言箱**（文字+语音文件），在线端立即收 `unpaired`。历史关系（`history.json`）保留 30 天——这是"找回TA"的凭据。 |
| `msg.post` | `kind:"text"`+`text`(≤500字) 或 `kind:"voice"`+`voiceId`(UUID)+`durMs`(0<d≤60000)，可选 `id`(UUID) | 已鉴权+已配对 | 给对端留言（对方离线也收）。发件限速 1 条/秒；被拒回 `msg.failed`。语音文件先经 HTTP `POST /voice` 上传（见 2.4）。`id` 为客户端预生成 id：合法且不撞号则服务端采用（撤回靠它对号），否则服务端另生成并以 `msg.sent` 为准。 |
| `msg.read` | `ids`[] | 已鉴权 | 阅后即删：从**自己**的留言箱删除这些 id（含语音文件），只能删自己的。 |
| `msg.recall` | `id`(UUID) | 已鉴权 | 撤回自己发出、**5 分钟内**的留言：未读的从对方信箱摘除（语音文件同删），成功双方各收 `msg.recalled`，被拒收 `msg.recall_failed`。 |
| `app.diag` | 见 2.3 | 已 hello | 服务端只落日志 `[diag.selfcheck]`，不转发。 |
| `app.version` | 见 2.3 | 已 hello | 服务端只落日志 `[diag.version]`，不转发。 |
| `diag.session` | 见 2.3 | 已鉴权 | 会话结束自报终止原因/末次 ICE 态/前后摇耗时。服务端只落日志 `[diag.session]`，不转发。 |
| `diag.event` | 见 2.3 | 已鉴权 | 会话中途事件自报（锁屏、采集成败、ICE 闪断与恢复、观看端零帧卡死、surface 生命周期）。服务端只落日志 `[diag.event]`，不转发。 |

未 hello 之前的一切消息被忽略；未鉴权（`authed=false`）时 `invite` 回 `peer.offline:not_authed`，其余媒体/呼叫类消息静默丢弃。

### 2.2 服务端 → 客户端

| type | 载荷 | 触发 |
|---|---|---|
| `registered` | `paired`(bool), `peerOnline`(bool), `hasHistory`(bool) | hello 成功后；`hasHistory`=存在 30 天内配对历史，客户端据此显示"找回TA"/启用摇一摇 |
| `peer.online` | — | 对端上线（本端已配对时） |
| `error` | `reason`: `bad_device_id` / `bad_token` | hello 校验失败；`bad_token` 后紧跟 close 4001 |
| `pair.code` | `code`(6位数字串) | pair.request 成功 |
| `pair.failed` | `reason`: `too_fast` / `unavailable` / `too_many_attempts` / `code_invalid` / `self_pair` / `already_paired` / `server_storage` | 配对各类失败。**客户端必须逐条给文案**：`already_paired` 的意思是"对方手机还绑着一台旧设备"（换机/重装 App 会换 deviceId），出路只有让对方先点「解除配对」，和"码打错了"完全相反（`PairFailMessagesTest` 与服务器源码对账，漏一条即红）。 |
| `paired` | `token`(32hex), `peerOnline` | 配对成功，双方各发一条；token 由客户端持久化用于后续 hello。找回确认/互摇直绑成功也走这条（bindPair 单一路径）。 |
| `pair.recall_sent` | — | 找回请求已转发给对方，等待确认（60s 有效） |
| `pair.recall_ask` | `from`(发起方 deviceId) | 老搭档发起找回请求，被召回端弹窗确认 |
| `pair.recall_failed` | `reason`: `no_history` / `peer_offline` / `peer_busy` / `declined` / `too_fast` / `expired` | 找回失败/过期（发起端；peer_busy 双方都收） |
| `unpaired` | — | 解绑成功（双方） |
| `incoming` | `from`(主叫deviceId), `iceServers` | 被呼叫。`from` 供双方同时呼叫（撞车）时按设备号确定性裁决 |
| `accepted` | `iceServers` | 对方接听 |
| `declined` | — | 对方拒接 |
| `call.canceled` | — | 对方撤呼 |
| `peer.offline` | `reason`(可选: `not_authed`/`too_fast`) | 呼叫时对端不在 |
| `bye` / `sdp` / `ice` | 原样转发 | 对端挂断/媒体协商 |
| `peer.gone` | — | 对端连接断开（服务端 close 时补发） |
| `msg.inbox` | `items`[]（留言条目） | hello 成功后，若有未送达留言（非空才发） |
| `msg.sent` | `id` | msg.post 成功回执（容量溢出挤掉的旧留言由服务端自行删除，不再通知） |
| `msg.failed` | `reason`: `unpaired` / `too_fast` / `empty` / `too_long` / `bad_recipient` / `bad_kind` / `bad_voice_id` / `bad_duration` | msg.post 被拒 |
| `msg.new` | 留言条目展开（`id,from,kind,text|voiceId+durMs,ts,expires`） | msg.post 时收件人恰好在线则直达 |
| `msg.recalled` | `id` | msg.recall 成功：发件人回执 + 收件人（恰好在线则）转发，双方把该条折叠成"撤回了一条消息"提示行 |
| `msg.recall_failed` | `id`, `reason`: `not_found` / `too_late` | msg.recall 被拒 |

留言条目结构：`{id:UUID, from, kind:'text'|'voice', text | voiceId+durMs, ts, expires}`。
容量与隐私：每收件人最多 20 条（溢出丢最旧，语音文件同删）、TTL 7 天自动销毁、`msg.read` 送达即删（阅后即删）；无厂商推送，**打开 App 才送达**。决策逻辑在 `server/src/messages.js#MessageBook`（纯逻辑，单测覆盖）。
撤回已知局限：服务端只保留 `id→{from,to,ts}` 发送索引（随信箱条目持久化，已 ack 条目重启后索引丢失），所以 ①旧版客户端（不带 `id` 的 msg.post、msg.sent 被忽略）的消息无法撤回；②服务端重启后，撤回中的"已被读过"路径可能退化为 `not_found`；③收件人已读且当时离线收不到 `msg.recalled` 时，对方本地聊天记录不追删（隐私模型：服务端已无内容，客户端历史不属云端管辖）。

### 2.3 ICE 凭证与遥测

- `iceServers` 由服务端签发（`server/src/turn.js#buildIceServers`）：`username = floor(now/1000)+ttlSec`（默认 ttl 3600s），`credential = base64(HMAC-SHA1(secret, username))`（reverse-proxy 限时凭证）。含 1 条 `stun:` + 2 条 `turn:`（udp/tcp）。
- `app.diag` 字段：`role`(viewer/sharer), `model`, `brand`, `os`, `sdk`, `rom`, `app`, `notif`, `overlay`, `acc`, `accConnected`, `accGestureCap`, `mic`, `battery`，外加扩展位（观看端 `ctlSupported`、共享端 `ctlAllowed`）。服务端只落诊断日志、不转发。
- `app.version` 字段：`localCode`, `localName`, `remoteCode`, `remoteName`, `hasUpdate`。
- `diag.session` 字段（**会话终止原因**，v0.2.55）：`role`(sharer/viewer), `reason`(`connect_TIMEOUT` / `ice_DISCONNECTED_TIMEOUT` / `ice_FAILED` / `capture_START_FAILED` / `projection_stopped`(仅 ≤0.2.63，见下) / `sdp_*` / `webrtc_start_failed` / `local_stop` / `user_hangup` / `remote_bye` / `peer_gone` / `call_canceled` / `local_close` …), `ice`(末次 ICE 态), `setupMs`(发起→连通), `liveMs`(连通→结束), `fg`(本端是否有 Activity 在前台), `screenOn`, `app`(版本名)，v0.2.59 起另带 `path`(末次选路缩码，同 `qos_tick` 的两字母语法)与 `proj`(采集侧状态 `starting`/`live`/`stopped`，观看端为 `-`)。服务端只落 `[diag.session]`、不转发。
  **`reason=local_close` 在共享端有两种成因（版本坑，10-19 t4 实测）**：观看端早在 v0.2.55 就在收尾处占了 first-wins 槽（`user_hangup`/`remote_bye`），而共享端的 `bye`/`peer.gone` 分支只调 `stopSession(sendBye=false)`——那条 `local_stop` 守卫是 `if (sendBye)`，进不去，于是"她挂的"与"服务器广播 peer.gone 拆的"都落成 `local_close`，得跟另一台设备的行去拼"谁关的"。v0.2.61 起共享端自己也报 `remote_bye`（收到 `bye`）或 `peer_gone`（只收到 `peer.gone`＝信令掉线，媒体可能本来好好的），两名字不许合并：`bye`=对方 App 明确挂断，`peer_gone`=服务器 30s pong 超时广播。**通路已在本机 t4 见光（PASS(模)，非真机）**：09:42:11 共享端 `reason=remote_bye ice=FAILED path=- proj=live setupMs=-1 liveMs=0`，同一毫秒前有 `peer_signal sig=bye`——这一通本来就是"从没连上过"（`setupMs=-1`），所以 `remote_bye` 只说明"是对端先发的 bye"，别读成"聊了几秒被挂断"；判"连上后被挂断"必须看 `liveMs>0`。观看端同批修掉的是另一件事：`finishWithCleanup` 原来是拿 `sendBye` 布尔值**反推**原因，而 `sendBye=false` 有两个语义完全不同的入口 ⇒ 现在由调用方显式交出 `endReason`，`call_canceled`（主叫在授权阶段反悔，那一通**从没连通过**，`setupMs=-1`）与 `remote_bye`/`peer_gone` 分家；布尔值只作兜底（本端主动挂断仍是 `user_hangup`）。两边都读得到 `peer_signal detail=sig=gone|bye`（v0.2.57 起），它记的是"到达瞬间的 ICE 态"，与 `reason` 互为对照而不是替代。
  **`projection_stopped` 的读法有两个版本坑（10-19 生产日志实锤 + 10-20 语义翻转）**：`MediaProjection.Callback.onStop()`
  在 ≤0.2.60 只把原因交给 `listener.onEnded` 而不占 first-wins 的槽，收尾 `stopSession(sendBye=true)`
  补的那条 `local_stop` 因此赢下来 ⇒ **≤0.2.60 日志里的 `reason=local_stop` 同时涵盖"她按了停止"和
  "系统撤销录屏授权"两种，不可二选一**。0.2.59 起可用同一行的 `proj` 反推（`proj=stopped` ⇒ 被撤销，
  `proj=live` ⇒ 她按的），0.2.61 起 `reason` 自己就出名分了、并且另有 `stop_tap` 指出她点的是哪个入口。
  **0.2.64 起这一格翻了个面：`reason=projection_stopped` 不再产生**——系统撤销录屏授权不再是会话终止原因，
  而是"画面停、语音与信令续、一键重开"（她锁屏是为了省电，而 MediaProjection 与音频/数据通道无关：
  10-19 双真机日志里撤授权那一刻 `ice=CONNECTED`、rtt 正常，只有画面死了；旧行为让对端听到的是挂断）。
  所以 ≥0.2.64 的正确读法是：**`diag.event projection_revoked`（detail `keepalive`）= 画面被系统撤了但会话还活着**，
  紧随其后若有一通 `diag.session`，它的 `reason` 与"撤授权"这件事无关；她重开授权成功 ⇒ `projection_resumed`，
  重开失败 ⇒ `projection_reopen_fail`（detail `no_data`=Intent 没带回来 / `startCapture`=新 token 起不来采集，
  两种出路不同：前者是通知投递链路问题，后者只能让她再授权一次）。`proj` 这一列仍然只描述会话**收尾那一刻**的采集状态，
  它不能告诉你中途有没有被撤过——两件事分开读，不许互为替代。
  ⚠️ **对端看到的差别**：≤0.2.63 撤授权 ⇒ 观看端收到 `bye`/会话结束；0.2.64 起 ⇒ 观看端收到 DataChannel
  `k=videooff`（横幅「对方画面已暂停·语音继续」并暂停 `video_stall` 判定），重开成功收到 `k=videoon` 才收横幅。
  **`screenon` 从这一版起不再等于"画面回来了"**（解锁 ≠ 重新授权），观看端若在 `videooff` 之后只等到 `screenon`
  必须继续挂着横幅——这条由 `SignalRouterTest`「解锁不许替对方撒谎」锁定。
  **`path`/`proj` 的三态读法（10-18 实测，别把"没有值"读成"没埋"）**：`_`=客户端这条线根本没发该字段（0.2.58 及以前恒为 `_`，即服务器白印了两天）；`-`=发了但当时确实无值；`live`/`h-s` 等=真读数。10-18 双模拟器两条 0.2.59 的 `diag.session` 实形为 `role=sharer reason=local_close ice=FAILED path=- proj=live` 与 `role=viewer reason=connect_TIMEOUT ice=FAILED path=- proj=-`——`ice=FAILED` 的会话从没选路过，`path=-` 是正当答案不是缺陷；观看端不采集，`proj=-` 同理。**`path` 想要真值必须等一次 established 的会话**。本机此前打不通是因为 `.env` 里 TURN 是占位值（模拟器 ICE 结构性 FAILED），**10-19 已按用户点头借线上 TURN 凭证解开**：借来之后同一次 t4 里 `gather_done` 出现 `r_1`、ICE 走到 CONNECTED，`diag.session path=` 第一次拿到两字母真值（属 `PASS(模)`：证"这一列会出真值、格式对"，不证 HyperOS 上同样出数）。
  **`path` 是闩锁而不是"最近一轮的原文"（v0.2.62）**：断会之后那一轮 stats 里没有 succeeded 的 candidate-pair，`selectedPath` 退回 `unknown`，若无脑覆盖，短会话（<10s，本来就来不及出例行 tick）的末次读数永远是"散会之后那条"⇒ `u-u`。现在只有"真读到选中对且类型认得出"才覆盖闩锁（`?->?` 是采集缺陷，不算新事实，同样不覆盖），从没读到过才留 `-`；`qos_bad` 的 `b=pth` 与选路变化判据仍看每轮原文，不看闩锁。
  这两列此前服务器印了两天全是 `_`：字段在服务端一直存在，但客户端从没 `put` 过（`safeTag(undefined)` = `"_"`）。**`_` 是"没埋"，`-` 是"埋了但这一侧没有这个东西"**，读日志时这两个必须分开——把 `_` 读成"走的是未知网络路径"会得出相反结论。**v0.2.58 起 `setupMs=-1` 表示从未连通**，`liveMs` 停在失去连通那一刻——此前 `connectedSinceMs` 会被 ICE 闪断清零，导致"聊了 18.9 分钟被拆"与"根本没连上"在日志里同形（0.2.55/0.2.56 生产日志的 liveMs 全部失真）。
  `screen_off_timeout` 随 v0.2.56 锁屏保活一起废除（锁屏不再结束会话，因此不会再产生该原因）。
  为什么需要：WebRTC 是媒体直连，服务器只转发 `sdp/ice`，看不见采集失败、启动失败、谁按的挂断。此前"米15 发起 4~6 秒即断"在服务器日志里只能看到一对没有原因的 bye。
- `diag.event` 字段：`role`, `event`（会话期 `screen_off`/`screen_on`/`capture_ok`/`capture_fail`/`ice_outage`/`ice_recovered`/`ice_failed`/`video_stall`/`video_resumed`/`viewer_paused`/`viewer_resumed`/`surface_created`/`surface_size`/`surface_destroyed`/`sink_attached`/`fgs_start_failed`/`webrtc_start_failed`/`leave_hint_screen_off`/`pip_enter`/`pip_exit`/`peer_signal`/`gather_done`/`qos_tick`/`qos_bad`/`stop_tap`/`projection_revoked`/`projection_resumed`/`projection_reopen_fail`（v0.2.64 起，共享端画面生命周期）；信令期 `signaling_down`/`signaling_up`/`app_exit`；待机期（v0.2.59 新增）`probe_start`/`heartbeat`/`net_up`/`net_down`/`net_type`/`doze_on`/`doze_off`/`bucket_*`/`wake_ok`/`wake_off`/`wake_fail`/`aexit`；补报队列自身 `session_end`/`backlog_drop`）, `ice`, `detail`(≤64字符), `fg`, `screenOn`, `app`。服务端只落 `[diag.event]`、不转发。
  **`role` 有三种**：`sharer`/`viewer` 是会话中的角色，`standby` 是**非会话期**（v0.2.59）。读日志时把 `role=standby` 当成"观看端"会得出完全错误的结论——待机掉线和观看端掉线是两件事。
  ⚠️ **所有 `diag.*` 都只在 socket 已鉴权（`hello` 带对了 pair token）之后才会被服务器处理**，未配对期间的遥测在服务器上必然为空。这条不是 bug 也不是埋点坏了：没鉴权的 diag 帧会被服务端直接丢弃。所以"米15 装了 0.2.59 却一条待机事件都没有"的第一因是**她还没配对**，先去看 `diag.version code=59` 有没有 `paired`。
  ⚠️ **`bucket_*` 的名字来自 `UsageStatsManager.getAppStandbyBucket()`**：`bucket_act`(10)/`bucket_wset`(20)/`bucket_freq`(30)/`bucket_rare`(40)/`bucket_rst`(**45**)。档位值不许凭记忆改——t0 直接拿编译 SDK 的 `android.jar` 跑 `javap -constants` 对表（RESTRICTED 曾被按记忆写成 50，而平台里 50 根本不存在，真机掉进 restricted 就会被读成"未知档"，恰好丢掉米15 待机断网最想要的那一格）。落在五档之外的值渲染成 `bucket_x<数>`，那是"ROM 给了个没见过的档"，不是埋点坏了。HyperOS 待机断网几乎都发生在掉到 `bucket_rst` 那一刻，detail 带 `prev=<上一档>`。**10-18 用 `am set-standby-bucket` 逼出一整条降级链**：`bucket_rare(prev=10)`→`bucket_rst(prev=40)`→`bucket_wset(prev=45)`→`bucket_act(prev=20)`——`prev=` 与档名两两自洽，且 `bucket_rst` 能出数就是 45 那格修对的直接证据（按记忆写成 50 的那版只会打成 `bucket_x45`）。注意**模拟器不会自然掉档**（锁屏静置 8 分钟里 `b=10` 恒 ACT），所以"HyperOS 自己会不会降到 rst"仍未采样。
  **10-19 第一条自然（非 `am set-standby-bucket` 逼的）边沿在 Mi10 真机出现**：灭屏待机中 `bucket_rare(prev=10)` → 6 分钟后 `bucket_act(prev=40)`，且当时 `dumpsys battery` 仍是 `USB powered: true` ⇒ ①"模拟器不会自然掉档"不再能外推到真机；②插着 USB 不阻止降档；③自然链目前落在 10↔40 之间，`rst`(45) 那一步仍未采到。
  ⚠️ **10-18 Mi10 真机（HyperOS，Android 12）第一次出数就是 `bucket_x5 detail=prev_-1`**——不是埋点坏了，是 ROM 给了个 AOSP 五档之外的数。拿系统侧对表：`dumpsys usagestats` 里 `STANDBY_BUCKET_CHANGED package=com.soul2soul.app standbyBucket=5 reason=d`（10-17 21:28 起），全机分布 `578×10 / 369×45 / 169×40 / 61×5 / 38×20 / 28×30`，另有 `com.miui.voiceassist` 在 `standbyBucket=50`。结论：MIUI 自己造了 `5` 和 `50` 两个档，`x5`/`x50` 是**忠实读数**；t0 的 javap 守卫只能校验 AOSP 那五个号，越界值必须保持原样透出、不许悄悄归并到最近的档（归并就等于把"ROM 私有省电档"这一格抹掉，而那正是米15 断网最可能的凶手）。读日志时看到 `bucket_x5` 应当判为"掉进 MIUI 未知档"，不是"读数失败"。
  ⚠️ **机型地板（minSdk=23）**：`pollBucket` 起手 `if (Build.VERSION.SDK_INT < 28) return` ⇒ **Android 7/8（含本机 Mi5）上 `bucket_*` 一条都不会有，心跳里的 `b=` 恒为 `x`**（`lastBucket` 保持 -1）。`x`=无读数，不许读成"0 档"或"档没变"，这一台的零采样也不是埋点坏了；`StandbyTelemetryTest` S10 盯的就是"闸门必须与调用点在同一个函数体里"。
  ⚠️ **`heartbeat` 的判据是"缺口"不是"到达"**：只在**已配对且不在会话中**时 300s 一条，detail 形如 `k=123456,g=300,n=wifi,d=0,b=10,w=1,s=1`（`k`=elapsedRealtime 秒=单调锚点，`g`=与上一条**真的发出去了的心跳**的单调秒差、`x`=上一条从未发出（两种成因，见下条警示），`n`=transport 代号 wifi/cell/vpn/eth/bt/unk，`d`=Doze，`b`=bucket，`w`=保活锁，`s`=信令在否）。连续两条 `k=` 差 >600s **且这段时间里没有会话** ⇒ 中间进程被冻结或被杀。设备时钟不可信（Mi5 实测慢 ~10.5h），**只用 `k=` 算间隔，绝不拿服务器行时间或 `at=` 相减**。
  ⚠️ **`g=x` 不等于"进程是新起来的"，`k=` 缺口也不等于"被冻"（10-19 米15 实测两个方向都误读过）**：`lastBeatSec` 只在心跳**真的发出**时才置位，而会话期间 `shouldHeartbeat` 把整段都抑制掉 ⇒ 跨一通电话之后的第一条心跳必然报 `g=x`（上面 10-18 那条 `k_3309_g_x` 属此类还是首条另说，但 10-19 她那条 `g_x` 确实是跨会话抑制）。同理 10-19 Mi10 的 `g_1200` 跨的正是一通 15 分钟的共享（`liveMs=916267`），是正常抑制不是冻结。**判"被冻/被杀"要的形状是"无会话、无事件、心跳整段消失"**：10-19 18:14~18:27 米15 那 13 分钟连 `screen_off` 都没发出来，复活时一次性补出 `heartbeat`+`signaling_down`+`doze_off`+`screen_on` 四条 `at_` 且**没有新 `aexit`** ⇒ 进程活着被冻；与之相对，17:46:45 那批复活带了 `aexit r_10_i_125_a_21` ⇒ 那是进程被停。两种死法只有靠"复活批次里有没有新 aexit"分开，拿缺口长度单独下结论会得反。日志侧口径：整格渲染成 `k_123456_g_300_n_wifi_d_0_b_10_w_1_s_1`（等号与逗号都被 `safeTag` 磨成 `_`），grep 时按 `k_` 后面的数字取锚点。**10-18 实测「配对·锁屏待机」这一格终于出数了**：`screen_off(k_3032)` 后静置 8 分钟，中途 `heartbeat detail=k_3309_g_x_n_cell_d_0_b_10_w_0_s_1` 准点到达（`k` 比 `probe_start` 那条的 3009 正好 +300 单调秒；`g=x`=本进程首条心跳，不是缺数），唤醒时 `screen_on(k_3512)`。这条是"待机期客户端一句话都不说"这个原始病灶的直接反证；**缺口判据（两条 `k=` 差 >600s）仍待一次真被冻/被杀的采样**。
  ⚠️ **`qos_tick` 每 10s 一条（仅会话 established 期间），detail 是定位语法不是 `k=v`**：`q=<rtt>,<选路>,<可用kbps>,<画质档>,<视频丢包×10>,<视频抖动>,<视频帧率>,<音频丢包×10>,<音频抖动>,<音频消隐>`，例 `q=29,h-s,1200,1598,5,21,29,0,3,0`。选路两字母 `h`=host `s`=srflx `r`=relay `o`=其它类型 `q`=选中了对但候选类型读不出 `u`=这一轮根本没有 succeeded 的 candidate-pair（v0.2.62 起 `u-u` 与 `o-o` 分家：以前两者都塌成 `o-o`，于是"这通到底走中继还是直连"在短会话里永远读不出来）（`h-s` 即本地 host 对远端 srflx = 局域网直连）；`x`=这一格没有读数，**不许当 0 读**（0 是"确实没丢包"，`x` 是"没测到"）。第 4 格是**画质档**：Android 13 及以下它就是采集长边（与真实像素一致）；**Android 14+ 它是码率档**——那一版起 MediaProjection 的 token 是一次性的，中途改分辨率必须 stop+start 采集，系统会当成第二次开采集并撤销授权（v0.2.62 双模拟器 API 34 实测 `SecurityException: Don't re-use the resultData` → 会话 4.5s 死），所以 14+ 只降码率、采集尺寸留在建会那一档（真实像素看同会话的 `capture_ok` detail）。第 7 格帧率 v0.2.62 起由累计 `framesSent`/`framesReceived` 按区间相减得到（这一版 libwebrtc 的 outbound/inbound-rtp **没有** `framesPerSecond`，直读永远是 `x`）。各项上限：rtt/抖动/消隐 999ms、可用带宽 99999kbps、边长 9999px、帧率 999fps、丢包 100.0%（=`pct×10` 的 1000）——**超出即夹**，读到 999 只能判"≥999"。夹这么狠是因为 `detail` 只有 ~47 可用字符（64 减去补报前缀 `at=<13位毫秒> `），被 `safeTag` 从尾部静默裁掉就什么都读不出来了。日志侧口径：`q_29_h-s_1200_1598_5_21_29_0_3_0`——逗号也被磨成 `_`，所以读的时候按 `_` 切位，而选路码 `h-s` 里的短横保留（`-` 在 `safeTag` 白名单里）。
  **每 10s 是节奏、不是唯一发射条件（v0.2.62）**：established 之后的**第一条**不等 10 秒，当场补发一条——她的典型故障是连上几秒就散，按 10s 节奏那条永远出不来 ⇒ 短会话零读数，等于又白埋一次。但这一条只当背景：它**不**驱动 `qos_bad`（`prevQos` 还不存在，而留下的三条判据全是相对上一条的差分，没有基线就没有"变化"），也**不**推进 `video_stall` 的连续计数（会把告警提前一格）。数一条会话的 tick 条数时要把它算进去：一通 8 秒的会话现在可以是 1 条而不是 0 条。
  **`qos_bad`（异常立即插一条，不等下一个 10s）detail 以 `b=` 开头标原因**，一个 tick 最多一条，顺序即严重度：`b=pth,<旧>><新>`（选路变化，`unknown` 变化不报）、`b=vl,<×10>`/`b=al,<×10>`（视频/音频丢包 ≥5.0%）、`b=rtt,<旧>><新>`（≥3 倍**且**基线 ≥60ms）。
  ⚠️ **v0.2.62 撤掉了 `b=fps0`**（本端这一格 0 帧）：静态画面单窗口 0 帧是常态——采集没变化就不出帧，实测 sharer 与 viewer 两端每 10s 各插一条，一分钟 12 条把 P0 补报配额灌满，真异常反而被挤掉；而它想指的"画面死了"已经有 `video_stall` 负责（连续 3 个完整 10s 窗口 + 排除本端停帧与对方锁屏）。同一个事实只留一个真值来源，读日志时**0 帧看 `video_stall`/`video_resumed`，不要找 `qos_bad`**；`qos_tick` 的第 7 格帧率仍是原始读数（`x`=没测到，不许当 0 读）。
  **`gather_done`（每会话至多一条）detail `h=<n> s=<n> r=<n> p=<n>`**：`r=0` 就是 TURN 一个中继都没分配到 ⇒ "必须走中继却分配失败"这一类断连此前只能靠猜，现在有自证。
  两个发射时刻**必须分开读**：ICE 收集正常走完 `COMPLETE` 时那条不带尾巴；**没走到 `COMPLETE` 就散会**（快速 `FAILED`/连接超时）时补一条带 ` inc=1` 的（日志侧 `…_inc_1`），发在 `diag.session` 同一批、socket 还能用的最后一刻。为什么要补：10-19 本机双模拟器 t4 实测**整轮 0 条 `gather_done`**——只监听 `COMPLETE` 等于在"TURN 没分配"这个唯一需要它的场景里闭嘴（与 0.2.59 修掉的 `wake_ok` 顺序 bug 同类"白埋"，S9 锁定：删掉补发那条 t1 即红）。读法三条口径：① `inc=1` + `r=0` 只说明"截至散会没拿到中继"，候选可能还没长齐，**不许**直接等同"TURN 服务坏了"；② `inc=1` 且**四格全 0** = 一条候选回调都没收到（`connectStartedAtMs<=0` 的客户端根本不报，所以全 0 不是"没埋"而是"真没候选"）；③ 不带 `inc=1` 的那条才是完整收集，`r=0` 可以按"TURN 分配失败"下结论。
  **`aexit`（上一轮进程死因，API 30+，仅在信令首次连通时报一次）detail `r=<reason枚举号> i=<importance枚举号> a=<多少分钟之前>`**：这是"米15 是被 ROM 杀了还是自己睡死"的远程答案。号—名对照**不许凭记忆写**，下面两行是机器读的（`scripts/test.sh` t0 拿编译 SDK 的 `android.jar` 逐一对表，改错一个号 t0 即红）：
  <!-- AEI-REASONS: 0=REASON_UNKNOWN 1=REASON_EXIT_SELF 2=REASON_SIGNALED 3=REASON_LOW_MEMORY 4=REASON_CRASH 5=REASON_CRASH_NATIVE 6=REASON_ANR 7=REASON_INITIALIZATION_FAILURE 8=REASON_PERMISSION_CHANGE 9=REASON_EXCESSIVE_RESOURCE_USAGE 10=REASON_USER_REQUESTED 11=REASON_USER_STOPPED 12=REASON_DEPENDENCY_DIED 13=REASON_OTHER 14=REASON_FREEZER 15=REASON_PACKAGE_STATE_CHANGE 16=REASON_PACKAGE_UPDATED -->
  <!-- AEI-IMPORTANCE: 100=IMPORTANCE_FOREGROUND 125=IMPORTANCE_FOREGROUND_SERVICE 200=IMPORTANCE_VISIBLE 230=IMPORTANCE_PERCEPTIBLE 300=IMPORTANCE_SERVICE 325=IMPORTANCE_TOP_SLEEPING 350=IMPORTANCE_CANT_SAVE_STATE 400=IMPORTANCE_BACKGROUND 500=IMPORTANCE_EMPTY 1000=IMPORTANCE_GONE -->
  读法：`r=11`=她在最近任务里划掉了 App；`r=14`=被系统冻结（HyperOS 的"省电"最可能就是这一档，也是"锁屏后一句话都不说"的候选死因）；`r=3`=低内存杀；`r=4`/`r=5`=Java/原生崩溃；`r=2`=被信号杀（含 native crash 的 SIGSEGV）；`r=1`=App 自己退出；`r=6`=ANR；`r=12`=依赖进程死了；`r=16`=**包被更新过**（=我们刚重装，复现期的自证噪声，别当成断连死因）；`r=13`=OTHER（系统没给原因）。`i=` 是死那一刻的进程优先级：`125`=前台服务在跑（保活语义活着）、`400`=已经掉到后台/缓存、`100`=还在前台——`r=14 i=125` 与 `r=14 i=400` 是两种完全不同的死法。
  ⚠️ 本条的对照表第一版是照记忆写的（把 `13` 说成低内存、`14` 说成用户手动停止），10-18 用 `javap -constants` 对 android-34/35/36 才纠正过来——与 bucket 档位表是同一个坑的第二次出现，所以两行表都进了 t0。`a=` 用 wall-clock 相减（AEI 的 `timestamp` 只有绝对时间，没有单调锚点可用），设备时钟在死 later 被校正过就会失真：负数一律报 `a=x`（"不知道多久以前"），**不许**夹成 `a=0` 冒充"刚刚死的"。`description` 是自由文本（可能含路径），按隐私线一律不读。
  **10-18 实测读数（用来校准眼睛，别一看到 `r=` 就当断连死因）**：`am force-stop` 后重开 ⇒ `detail=r_10_i_100_a_0`——是 `REASON_USER_REQUESTED`+前台，**不是** `r=11`（11 要她在最近任务里划掉）；`adb install -r` 重装 ⇒ `r_16_i_125_a_0`（包更新+前台服务在跑，属复现期自造噪声）。两条都证明"首次信令连通时读一次"的时机是对的：进程刚死、AEI 还留在系统那侧。
  ⚠️ **但"取最新一条"不等于"取到本次死因"——AEI 落库会滞后于我们的首次连通**。同一台 Mi10 真机上，`adb install -r`（必然杀进程）后重开，`aexit` 报的是 `r_10_i_125_a_179`：`r=10`+`a=179 分钟` ⇒ 拿到的是**上一次**用户侧被杀的记录，本次重装的 `r=16` 那一刻还没写进去。模拟器上同一动作报 `a_0`，所以这不是代码差异而是 ROM/写盘时机差异。读法 consequences：**`a=` 大于本次会话存活时间时，这条描述的是"更早的一次死"，不能当作本轮断连的死因**；反过来，她那边真实断连后重新打开 App，`a` 应该等于"从断到开"的分钟数——这正是想要的那格。想拿本次死因得等下一次信令连通再读（`aexit` 每个进程只读一次，所以一次复现轮里最多一条）。
  `app_exit`（detail `why=destroy|unpair`）只代表**客户端主动走到收尾**，不许反推成"关闭码 1000 出现了"：`SignalingClient.close()` 本来就发 `close(1000,"bye")`，而 Doze 下 TCP 先死时这个帧根本送不出去（10-18 全天 0/40 条 code=1000 就是这个原因）。
  **`why=destroy` 自本次修复（commit 3f8797b）起只以补报行出现（`detail=at_…_why_destroy`），实时行从此不再有这一格**——⚠️ **这条行为还没随任何已发布版本上线**：现网最新是 0.2.63，它的日志仍是"当场丢"，所以下文那句"全天 0 条"在 0.2.63 上会照样成立，别把它误读成修复失效：10-19 生产日志第一次给出反例对——17:48:10 两端**同时**留下 `close code=1000 reason=bye`（证明 `onDestroy` 跑了、close 帧发得出去），而 `app_exit` 一条没到（全天 0 条）。根因不是通路而是顺序：`reportEvent` 走乐观发送（写进 socket 缓冲就算发，无 ack），而它下面第三行就是 `client.close()`，还没出去的帧连同连接一起被关掉。修法=临终那条改走 `DiagBacklog.enqueueSync`（同步落盘，不等 io 线程，因为进程可能在这行之后立刻没了），下次连上按"至少一次"重放。两条读法：① 它到达得**很晚**（她下次打开 App 并连上信令时），`at=` 才是退出时刻，服务器行时间不是；② 她若再也不打开 App，这条永远不到——这与旧版"当场丢"相比只是多了机会，**不许**把"没有 app_exit"读成"她没退过"。`why=unpair` 仍走实时发送（那一刻 socket 活着且后面没有 `close()`），两个入口不共用一条"等下次开机才到"的路径；这一分工由 `StandbyTelemetryTest` S11 锁（改回 `reportEvent` 或把 `enqueueSync` 甩给 io 线程，各自实测变红）。
  `stop_tap`（detail `src_main_page|src_notify|src_unknown`，v0.2.61 起）记的是"**哪个控件被点了结束**"这个输入事实：共享端的两条结束入口（App 内"结束"按钮、FGS 通知栏动作）走的是同一条 `ACTION_STOP`，日志里原本只留下同一个 `reason=local_stop`。它**不是**第二个终止原因真值源——`diag.session.reason` 仍然只有那一处，`stop_tap` 只回答"点的是哪一个"。三连读法：有 `stop_tap` ⇒ 她确实点了停止（`src_` 指出是哪一处）；无 `stop_tap` 而 `reason=projection_stopped` ⇒ 系统撤销了录屏授权；无 `stop_tap` 而 `reason=local_stop`（≤0.2.60）⇒ 二者不可辨，只能退回去读同一行的 `proj`。
  **`r=10 i=125` = HyperOS/MIUI 的省电杀进程指纹（10-19 在 Mi10 上双侧互证，这是本项目第一次拿到"待机为什么断"的确定答案——但答案只到"框架被要求停止进程"这一层，`r=10` 不区分谁要求，见下文 ⚠️ 段）**：生产日志里 `106db351` 的心跳从 18:29 到 21:04 **连续 33 条准点 `+300` 单调秒**（全程 `screenOn=false w_1 s_1 b_5 d_0`＝锁屏、锁在、socket 在、档没动），21:07:09.990 socket `close code=1006 aliveFor=9121611ms`（2.5h 好连接被外部切断，**不是** pong 假死），之后 10 小时零读数；07:03 拉起后首次连通即报 `aexit detail=r_10_i_125_a_596`，而 `596 分钟前`正是 21:07。设备侧独立核对：`dumpsys activity exit-info com.soul2soul.app` 的 #0 = `timestamp=2026-10-18 21:07:09.676 reason=10 (USER REQUESTED) importance=125`，`events` buffer 同一时刻有 `am_kill … due to AutoPowerKill`。**读法**：一次"待机后联系不上"的正确判据是 **最后一条 `heartbeat` 的时刻 + 复活时那条 `aexit` 的 `a=`**，两者对上就是"被杀"；只看 socket `code=1006` 会把它误读成网络断。注意**别把这套结论搬到米15**：她 22:10/22:37 两次是连上 2.5~6.9 秒就 `1006`，那是另一种签名，且 `r=` 的真值必须由她那台设备自己报出来（计划里猜的 `SIGNALED`/`LOW_MEMORY`/`FREEZER` 已被 Mi10 的实际答案否证）。两条口径顺带钉死：① 本 ROM 的 `dumpsys activity exits` 子命令**不存在**（`Unknown command: exits`），要看 AEI 只能用 `dumpsys activity exit-info <pkg>`，也就是说**没有我们这条埋点就根本读不到**；② `ApplicationExitInfo.description`（这里写着 `stop com.soul2soul.app due to AutoPowerKill`）**客户端仍然不读**（隐私线），上面这句是 ROM 侧 dump 出来的对照、不是 App 上报的。
  ⚠️ **但 `r=10` 本身判不到"是谁下的手"（这条不是新观测——矩阵里早就记着模拟器 `am force-stop` ⇒ `r_10`，新的是把它搬进 Mi10 那条结论旁边：10-19 补报实验又踩到一次，才发现散文段落一直在替"省电杀"下确定结论）**：本轮为触发补报在 emulator-5554 上执行 `adb shell am force-stop`，复活即报 `event=aexit detail=r_10_i_100_a_0`（10:36:02.647，`a_0`=刚刚）。也就是 **adb force-stop、她从最近任务里划掉、ROM 省电代劳** 这三种来源在 AEI 里都是 `reason=10 (USER REQUESTED)`。所以 Mi10 那次归因到"省电杀"靠的是**同刻两条外部读数**（`dumpsys … exit-info` 的时刻 + `am_kill … due to AutoPowerKill`），不是 `r=10` 自己；米15 既接不了 adb 也没有 dumpsys，**单凭 `r=10` 无法区分"她划掉了"与"ROM 杀了"**，那一格必须保持 ⬜，除非另有独立读数（她承认划过、或 kill 时刻系统性地与她的亮屏操作无关）。判"待机为什么断"的正确组合仍是 `heartbeat` 断崖 + `aexit` 的 `a=`，只是结论停在"框架被要求停止进程"这一层。
  ⚠️ **复活窗口有一格可能是真缺陷、但目前只是注入场景**：06:59 在 `mWakefulness=Dozing` 下 `am start` 拉起 App，进程活着（`S2S-Main: update check: remote=59 local=59` 说明 HTTPS 出得去）、`Presence.ensureStarted()` 也调了，但 AMS 侧记录是 `app=null createdFromFg=false startForegroundCount=0` ⇒ **FGS 启动被后台限制静默拒掉，进程"在线"却永远连不上信令，而且这种状态下连埋点都发不出来**；把屏幕真正点亮并解锁后重新 `am start`，同一服务立刻 `uidState: FGS / startForegroundCount=1` 并在 07:03:18 注册成功。这不等于她手机上会发生（她要点亮屏幕才能操作），所以只登记为**待复现的假在线死路**，不许写成已证实的根因。
  **`echo_ack` 只到"提交"这一跳（v0.2.65，观看端发）**：`detail=e_<最近一次>,x_<本轮最大>,m_<配不上数>,p_<还在等>`，
  量的是**「我收笔 → 她画布落墨并回 ack」**，走的是与笔迹同一条 anno 队列（`k="a"`，见 §3.1）。
  它**不含**采集/编码/网络/解码/渲染，所以这一列不是"回显总延迟"，**不许**在文档或结论里写成 `echoMs`。
  它的用途是给观看端 `HANDOVER_FADE_MS=150` 做下界校验：ack 都到不了的话，视频里的成品更到不了。
  三条读法：① **`0.2.65` 内部有"修 echo_ack 谎报积压"那个提交分界，两边口径不同**（见下条）；
  ② 分界之后 `m`/`p` 大于 0 才是**正常且有意义**的（anno 容丢包 ⇒ `p`＝真的还在等的笔迹数；
  旧版分界之前它被补发污染过，`p` 每笔 +1 是假积压）；整段缺失也不等于埋点坏了——客户端 5s 节流
  ⇒ 这条是重复采样，单条丢掉不丢结论，所以它降为 P1；
  ③ 旧版对端（≤0.2.64）根本不发 `k="a"` ⇒ 观看端只会走 600ms 兜底，`p` 会一路涨到 32 之后 `m` 才开始涨，
  这是**版本差不是故障**。
  ⚠️ **已修（0.2.65 内的分界，未单独占版本号）**：18:36~18:39 双模拟器那轮实测到 `p` 从 0 单调涨到 14、
  而 `e` 每笔都是新鲜值、`m` 恒 0——**这不是"丢包"，是账本自己造出来的积压**：ack 到达时条目已被
  `remove`，300ms 后的收笔补发 `{k:"e",id}` 在 `putIfAbsent` 上"重新登记成功"，而共享端那一笔早已提交
  （`SharerStrokeBuffer.end` 幂等返回 null ⇒ 不会有第二个 ack），于是每画一笔留下一条永久配不上的记录，
  到 `CAP=32` 之后还会开始把真正还在等的笔迹挤出账本、把 `m` 读成假故障。
  修法是账本里记一张同样有界的"已 ack id"表（`acked`＝LinkedHashSet 保插入序，超过 CAP=32 从迭代头淘汰最旧；
  补发只晚 300ms，不可能被 32 条别的 ack 挤出去），`onStrokeSent` 与 `onAck` 两处都要摘/记，`reset()` 一起清
  ——只记不摘会把还在等的算成已交棒，只摘不记就是这次的真机现象（补发把它复活成一条永久等待）。
  两条用例盯住：`resendAfterAckDoesNotReopenTheEntry`（单条）+
  `consecutiveStrokesLeaveNothingWaiting`（连画 40 笔 > CAP，`p=0,m=0`）；实测把 `id in acked` 抽掉即红。
  取证时**这条分界与版本号无关**：同是 `app=0.2.65`，看到 `p` 一笔一涨就说明那台跑的是修前的包，
  判"交棒遥测坏了"之前先对提交 `1da0158`。
  **`ice_failed` 与 `ice_outage` 必须在日志里分得开**：`ice_outage`（DISCONNECTED）会等恢复、`ice_failed` 是打洞彻底失败（detail 带 `established=`、`restarts=`、`path=` 的缩码）。取证必须排在恢复策略判定之前，否则 `established` 已被改掉。
  下面这一行是**机器读的**：`DiagEventRegistryTest` 把它与 main 源码扫出的事件名逐一比对，两个方向都不许有差（新增事件名不写进来，t1 直接红；写了没在发，也直接红）。以 `_` 结尾的是前缀项——`bucket_` 覆盖 `bucket_act`/`bucket_wset`/`bucket_freq`/`bucket_rare`/`bucket_rst`。上面那些散文段落是会腐烂的，这行不会。
  <!-- DIAG-EVENT-NAMES: screen_off/screen_on/capture_ok/capture_fail/ice_outage/ice_recovered/ice_failed/video_stall/video_resumed/viewer_paused/viewer_resumed/surface_created/surface_size/surface_destroyed/sink_attached/fgs_start_failed/webrtc_start_failed/leave_hint_screen_off/pip_enter/pip_exit/peer_signal/gather_done/qos_tick/qos_bad/stop_tap/signaling_down/signaling_up/app_exit/probe_start/heartbeat/net_up/net_down/net_type/doze_on/doze_off/bucket_/wake_ok/wake_off/wake_fail/aexit/session_end/backlog_drop/projection_revoked/projection_resumed/projection_reopen_fail/echo_ack/aud_started/aud_stopped/aud_silent/aud_tick/aud_play/aud_playback_error -->
  **FR-16 设备内录一族（`aud_*`，`role=sharer` 是采集侧、`role=viewer` 是放音侧）**：`aud_started detail=sr_48000_ch_2_c_aac_r_<起因>`
  （起因 ∈ toggle_on/peer_ack/channel_open/live/rebind，回答"这次采集是谁叫起来的"）、
  `aud_stopped detail=r_<出口>`（共享端出口 ∈ toggle_off/projection_stopped/session_close/channel_closed/record_error/codec_error/service_destroy/rebind，
  **七条出口 + 一条重绑共用这一个发出点**，采集线程的 finally 是唯一发射口——分成两处发迟早漏掉一条路径；
  观看端同名事件，出口 ∈ channel_closed/session_ended/activity_destroy/codec_error，同样只在播放线程的 finally 发一次，
  靠 `role=` 分辨是"他没在采"还是"她没在放"）、
  ⚠️ **`r_<出口>` 从 10-22 的修复起才是真读数**：此前 `stop(exit)` 把参数直接丢掉、采集线程自己 `var exit = Exit.SESSION_CLOSE` 收尾，
  于是**除"线程自杀"以外的六条外因出口全部打成 `r_session_close`**（10-22 双模拟器实测：关开关报的就是 `r_session_close`）。
  修法与观看端 `DeviceAudioPlayer.pendingExit` 同一条纪律——停机原因由调用方传进来、线程不许猜（`start()` 清零、
  finally 里 `pendingExit ?: exit`）。**读旧日志时 `r_session_close` 有两种成因**（真的是会话结束 / 只是有人调了 stop），
  这一格无法回溯区分；锁定用例 `stopPropagatesExitToStoppedTag`，把回退写回 `SESSION_CLOSE` 实测变红。
  `aud_silent detail=f_25`（沿触发：一段长静音只报一条）、
  `aud_tick detail=d_<ms>_q_<字节>_l_<丢帧>_di_<编码器没收下输入>_do_<编码器这一帧没出产物>_ds_<通道拒收或帧超限>_b_<字节>`（10s 一条，与 `qos_tick` 同族、已进 P2）、
  `aud_play detail=dl_<放音延迟ms>_q_<排队帧>_l_<迟到丢帧>_w_<线格式不符帧>_t_<窗口ms>`（观看端 10s 一条，同进 P2）。
  观看端 `AudioTrack.write()` 返回错误时另发 `aud_playback_error detail=r_<playback_error>`，与 codec/dequeue 失败的 `aud_stopped r_codec_error` 区分；这不等同于共享端采集失败。
  ⚠️ **`aud_tick` 的 `d_` 是发送端的帧间隔（节流自证），不是单程延迟**：两端没有共享时钟，
  真正的"她听到晚了多少"只能由观看端的 `aud_play detail=dl_…` 给出；读 `d_` 只能判"采集线程有没有在正常节奏上跑"，
  把它读成延迟＝把这一族的第一个格子就读歪。
  ⚠️ **`l_` 单独一个数不判因，必须与 `di_/do_/ds_` 成对读（10-22 双模拟器实测出来的第二个缺陷）**：
  采集跑约 80s 后长期报 `d_21_q_0_l_469_b_0`——**内录明明在有声**（`dumpsys audio` 能看到那条 tone 的 AudioTrack 在 started），
  而 469 帧全丢、一条都没上线。根因在编码器而不是通道：一次输入只取**一个**输出，
  而 AAC 编码器会偶发"一次投喂吐两帧"（起流头几拍、CBR 的填充帧），输出队列于是每发生一次就永久多囤一个；
  囤满之后它不再吃新输入，`dequeueInputBuffer` 从此**恒返** `INFO_TRY_AGAIN_LATER`
  （不是"等一下再来"，是再也没人消费那些输出）。
  修法是每帧把可用输出**排空到有界上限**（`MAX_DRAIN_PER_FRAME=4`：不写上界的话一次突发能把 21ms 的节拍拖死）。
  遥测侧的教训是这条只有拆开三类丢因才可诊断——`di_`=编码器不收输入（积压的特征形状）、
  `do_`=收了输入但那一轮没出产物、`ds_`=产物超线或 `sink()` 返回 false（通道关/背压）。
  **`l_>0` 而三类全 `0` 是第三种情形，10-22 之前无法表达**：旧格式里 `q_`/`b_` 也分不开"通道是 null"和"通道是 0 字节"，
  于是这条 bug 在日志里的形状与"她挂断了但线程还在跑"几乎一样。
  锁定用例两条：`DeviceAudioTapWiringTest.encoderOutputIsDrainedAndDropCauseIsSplit`（排空循环的界 `MAX_DRAIN_PER_FRAME` 被写小、或写回"取一帧就走"即红）、
  `DeviceAudioPolicyTest.audTickDetail_keepsDropCauseSplitAndBytesTrackSends`（`di_` 改名即红）；
  `b_` 由**实际发出的帧数**算，不是由 `l_` 反推——`l_` 涨而 `b_` 不动才是"积压"的形状。
  ⚠️ **上面那条排空修复本身在第一版里是致命的，读数形状是「`aud_started` → 约 300ms → `aud_stopped r_codec_error`，一条声音都没出去」**（10-22 装机实测，重复三轮）。
  `dequeueOutputBuffer` 的负数返回值里有三种，只有一种是故障：`INFO_TRY_AGAIN_LATER(-1)` 是"这一拍没有"、
  `INFO_OUTPUT_FORMAT_CHANGED(-2)` 与 `INFO_OUTPUT_BUFFERS_CHANGED(-3)` 是**事件**（AAC 编码器起来时必定先报一次 format changed，
  之后每次格式/缓冲集合变动再报），把它们一律当"编码器坏了"往外返回 ⇒ 排空循环第一次转到 -2 就 self-kill。
  同一段里还叠了第二个更隐蔽的坑：`runCatching { … }.getOrDefault(-1)` 把**抛异常**也压成了 `-1`，
  而 `-1` 恰好等于 `INFO_TRY_AGAIN_LATER` ⇒ 真出故障时读起来像"只是没帧"，而没帧时读起来像"坏了"，两种病在同一张日志里长得一模一样。
  修法：哨兵换成不可能与任何 codec 返回值撞上的 `EXCEPTION = Int.MIN_VALUE`；负数分支里显式放行那两个事件码（`continue` 不是 `break`，
  放过了还要继续排空，否则积压那条路又回来了）；排空的 `for (attempt in 0 until MAX_DRAIN_PER_FRAME)` 因此必须是**尝试次数上限**
  而不是产出上限——一长串连续负数事件不能把循环变成自旋。
  锁定用例两条（同一条用例里，实测各抽一次即红）：把 `INFO_OUTPUT_FORMAT_CHANGED` 那一支**注释掉**（token 还留在注释里）
  ⇒ 报「INFO_OUTPUT_FORMAT_CHANGED 是正常事件，不许当故障」红；两处 `getOrDefault(EXCEPTION)` 里**只改掉第一处**成 `getOrDefault(-1)`
  ⇒ 报「两处 getOrDefault 只能用那个不与负数码重叠的哨兵」红。
  这两条各自还纠了一个空锁：前者要求锚点排除注释行（只在注释里提一句事件码、代码照旧当故障，旧断言 `any { contains(token) }` 读不到）；
  后者从 `any` 换成"函数里**每一处** `getOrDefault(` 都必须是 EXCEPTION"（两处改一处，`any` 会被另一处顶绿）。
  **教训记在这里**：这三轮的读数全都来自"我上一轮的修复"，不是来自原始代码——所以任何排空/重试类改动装机后必须先量"起了之后还在不在"，
  只看 `aud_started` 出条就判通过会直接把 self-kill 当成工作正常。
  ⚠️ `aud_play` 的 `dl_` 目前是**常量读数**（`AUD_PLAYOUT_DELAY_MS=200`，它报告的是"现在用的哪一档"，不是实测到的滞后）——
  这一族真正缺的实测是"她的 `l_` 在非弱网条件下是否持续 >0"，`l_=0` 才说明 200ms 够用；调这个值之前必须先有真机 `aud_play` 样本。
  10-22 双模拟器给了**第一份（模）读数**：`l_` 是**累计**值，58 个窗口（≈580s、约 2.7 万帧）里它停在 **54**（≈0.2%），
  而形状是"平一段、跳一格"——**稳态窗口里 `l_` 一格都不涨**（连续 8–10 个窗口读同一个数，`q_` 稳在 9–11 帧＝192–235ms，正对着 200ms 这一档），
  每一次上涨都落在**含 live↔silent 边沿**（`q_` 被放空后重新堆）或**宿主抖动**（同一条里 `t_` 从 10001 涨到 10165）的那一拍。
  ⇒ 在这一台上 200ms 这一档够，代价集中在状态边沿而不是稳态。
  ⚠️ 这一条**不能**外推成真机结论：两台模拟器在同一台宿主机上、走的是本机回环 + 中继 TURN，不是无线网络；
  真机上"她的 `l_` 是否持续 >0"仍是要重取的那一格。
  `w_>0` 只可能是线格式不自证（解码器版本不符/帧被截断），`l_>0` 才是抖动超了硬上限（400ms），两条不许混着读。
  `probe_start` 是探针存活性的对照点：只有 `probe_start` 而没有后续任何边沿事件 = 注册成功但状态一直没翻转（或进程被冻），与"探针根本没起来"是两种情况。
  ⚠️ **`wake_ok`/`wake_off` 曾经 0 条，根因是接线顺序而不是通路（10-18）**：`PresenceService` 里 `updateWakeLock()` 排在 `StandbyProbe.start()` 之前，首次 acquire 的回报落在探针 `running=false` 上被直接丢弃——而"待机时保活锁到底拿到没有"正是待机断连要查的第一件事，等于白埋。改成**先起探针再拿锁**（解绑侧反过来：先放锁再停探针，否则 `wake_off` 同样发不出去）后，15:00 双模拟器装上即出 `event=wake_ok detail=k_3732`，两台各一条（一条直发、一条经补报带 `at_`，两条投递路径同时见光）。顺序已锁进 `StandbyTelemetryTest` S8：换回去即红（已实测）。**同一条修复也让心跳里的 `w=` 从撒谎变成真**：修复前 14:50~14:59 四条 `heartbeat` 全是 `w_0` 而锁其实拿着（`lastWakeHeld` 从没被喂过），修复后 15:05:40 `k_4032 … w_1 s_1`、15:05:43 `k_3958 … w_1 s_1` 两台同时翻正——读旧版本的 `w_0` 时**不许**当成"ROM 把锁放了"，那是埋点顺序缺陷，0.2.59 装机后的版本才有可信的 `w=`。
  **10-18 本机 dev server 的出数清单（米15 装 0.2.59 之前，这就是证据的唯一形态；都是模拟器，不许读成"真机已验"）**：已出数 `probe_start`/`heartbeat`/`screen_on`/`screen_off`/`net_up`/`net_down`/`doze_on`/`doze_off`（`cmd deviceidle` 逼出）/`bucket_act|wset|freq|rare|rst`（`am set-standby-bucket` 逼出）/`aexit`/`wake_ok`/`signaling_up`/`signaling_down`/`ice_failed`/`detail=at_` 补报；**仍零采样且原因各异**——`qos_tick`/`qos_bad`（要 established 的媒体会话，本机 `.env` 的 TURN 是占位值、模拟器 ICE 结构性打不通）、`gather_done`（当时和上面归为同一条原因，**10-19 复查更正为埋点缺陷**：同轮 `ice_failed` 已出数而它 0 条，因为发射点只挂在 `onIceGatheringChange(COMPLETE)` 上，快速 FAILED 的会话根本走不到收集完成 ⇒ 已在收尾补一条 `inc=1` 的发射点，S9 锁定；**10-19 07:42 复跑 t4 已出数**，共享端 `detail=h_6_s_0_r_0_p_0_inc_1`、观看端 `h_2_s_0_r_0_p_0_inc_1`（`app=0.2.60`，本机 `.env` 未配 TURN 故 `r=0` 与预期一致）⇒ 这一项从零采样名单里划掉，改判 `PASS(模)`)、`net_type`（要一次真实换网且前后 transport 代号不同）、`backlog_drop`（要 P0 积压超 150 条）。**10-19 从这条名单里划掉两项**：本机双模拟器点"解除配对"后，发起端与被解绑端**各**报了一条 `app_exit detail=why_unpair`（渲染成 `why_unpair`）与一条 `wake_off detail=k_…`，且顺序是 `app_exit`→`wake_off`→探针停——这正是源码注释要求的那条顺序（放锁排在 `StandbyProbe.stop` 之前，否则 `wake_off` 发不出去），运行时第一次成立；同刻之后服务器日志里 `event=heartbeat` **0 条**，证"解绑即关心跳"不是只写在单测里。⇒ 这两项改判 `PASS(模)`；仍零采样的是 `wake_fail`（要 acquire 真抛异常），以及 `app_exit why=destroy`（要 `onDestroy` 那条口，10-19 这次没走到）。零采样不等于埋点坏了，但也**不许**当成"已验证"。
  **10-19 10:36 `backlog_drop` 出数（双模拟器，注入式饱和 ⇒ `PASS(模)`）**：往 `files/s2s-diag-backlog.ndjson` 灌 302 条 P0 行（52KB，**故意压在 96KB 体积闸以下**，这样触发修剪的只能是补报路径、不是入队路径），冷启动后第一次复活 drain 取走 100 条、余下 203 条走 `BacklogTiers.trim`（配额 150 ⇒ 丢最旧 53 条、并在幸存队列头部插一条标记）；第二次复活时这条标记**作为批内第一条**到达：`[diag.event] 36a5e8b2-… role=- event=backlog_drop ice=- fg=false screenOn=true liveMs=-1 detail=at_1792377362272_dropped_53 app=-`。三条读法：① `dropped=53` 与配额数学自洽（302 条种子 + 1 条真实入队 = 203 条余量，`203-150=53`），说明计数器数的是它自己丢掉的东西、不是装饰；② **标记行的 `role=-`/`app=-`/`fg=false`/`screenOn=true` 是占位默认值、不是读数**（`BacklogTiers.dropMarker` 只填 `event`/`detail`/`at`），别拿它们当"淘汰发生时设备在后台"的证据，认领机器只能靠 deviceId；③ 它证明的是"淘汰会自我上报 + 标记能跨进程重启活到下一次复活"，**不**证明米15 真会积压到超配额（那要一次 P0>150 的真实断连，仍 ⬜）；④ 它**不存在**"一条实时 + 一条 `at_`"两次投递——标记只在 `trim` 里产生、只可能随补报出通道，所以 `repro-capture.sh summary` 对它单独标注"唯一投递"：套用"总数−副本数"的去重口径会把它算成 0 次，等于把已经出过数的格读成没响。顺带一条冷启动口径：这一轮在队列尾部看到的全是 `probe_start`/`bucket_act`/`wake_ok`/`net_up`——进程刚起来时 socket 往往还没连上，所以这几条**天然会走一次补报**，日志里到达时刻晚于发生时刻属预期，判因果一律看 `detail` 里的 `at_`（同一 reason 也解释了他们那台为什么常见 `at_` 前缀）。
  **10-18 18:24~18:39 Mi10 真机 + 线上生产 server 的出数清单**（`repro-capture.sh` 副本 `~/s2s-repro/20261018-182228`，deviceId `106db351-…`，`app=0.2.59`）：已出数 `probe_start`/`bucket_x5`/`wake_ok`/`net_up`/`net_down`/`net_type`（**正反两向**，实测细节见下文 18:35 那条）/`aexit`/`screen_on`/`screen_off`（非会话期，含 MIUI 自己点亮屏幕那两次）/`heartbeat`×3（`k_796302 g_x`→`k_796602 g_300`→`k_796903 g_301`，全程 `w_1 s_1 b_5 d_0`）/`signaling_down`/`signaling_up`/`detail=at_` 补报。**真机侧仍零采样**：`doze_on`/`doze_off` —— **结构性**，Mi10 在 deep-Doze 白名单里（`dumpsys deviceidle whitelist` → `user,com.soul2soul.app,10442`，电池优化=不限制），AOSP Doze 的网络限制对它根本不生效；这一格只能米15 出，且**米15 有没有被同样加白正是待查项**（她在 App 里点过"不限制"= 加白 ⇒ 若仍断连则 Doze 不是凶手；没点过 ⇒ `doze_on` 会先出现）。另 `wake_off`/`wake_fail`/`app_exit`（要她侧解绑或进程真被杀）、`backlog_drop`（要 P0>150）、`qos_tick`/`qos_bad` 与 `diag.session` 的真实 `path`/`proj`（`gather_done` 已从这一组摘出去：收尾那条 `inc=1` 补上之后，只要会话真的发起过连接就会出数、不再要求 established，见上面 `gather_done` 条；这一组仍然都要一次 established 媒体会话，而**该会话的任一端跑 0.2.59 就够出那一端的读数**——米15 实测读数仍是 58（0.2.59 于 10-19 06:52:41、0.2.60 于 10-19 08:20:47 上通道，她打开 App 才会检更，装没装上以 `diag.version code=` 为准），所以当前唯一可行的采法是"她发起共享、Mi10 作观看端接"，那时 Mi10 的 `diag.session path=` 应立即从 `_` 变成两字母码、`proj=-`；她装上 60 之后两端都能出，且 `proj=` 会顺带回答"连上就断"到底是她按了停止还是系统撤了录屏（见上文 `diag.session` 的版本坑）。）
  **10-19 17:49~18:30 双真机 + 线上生产 server 的出数清单（`~/s2s-repro/20261019-174950`，现身份两端 `app=0.2.63`）**——这一轮是 0.2.59 计划的 Verification 4，把上一条里"依赖米15"的那几项一次结清，但**必须按设备分账读**（`repro-capture.sh summary` 给的是合并数，合并数会把归因读反）：
  - `deba9443`＝**米15**（接不了 adb 的那台）：`qos_tick`×138、`gather_done`×2、`qos_bad`×3、`capture_ok`×2、`heartbeat`×1、`doze_off`×1、`bucket_act`×1、`aexit`×1（`r_10_i_125_a_21`）、`net_up`×3/`net_down`×1/`net_type`×2（含 `from_wifi_to_cell`）、`probe_start`×1/`wake_ok`×1/`signaling_up`×2/`signaling_down`×2/`screen_on`×2/`screen_off`×1，其中 **12 条走补报（`detail=at_`）**。⇒ "待机盲区"与"媒体读数"在这台取不到 logcat 的手机上都出数了；`doze_off` 是**本项目第一条真机自然 Doze 边沿**（不是 `cmd deviceidle` 逼的），`bucket_act` 同时证米15 API≥28（不是 Mi5 那种结构性缺席）。
  - `1a1d64ea`＝**本机 Mi10 的现身份**（0.2.63，本轮观看端）：`qos_tick`×138、`video_stall`×9、`heartbeat`×4、`gather_done`×2、`surface_created/size`、`viewer_paused/resumed`、`pip_enter|exit`、`peer_signal`、8 条补报。
  - `106db351`＝**同一台 Mi10 上的另一个 App 身份**（停在 0.2.59，本窗口最后一次上报 17:24:57）：`heartbeat`×28、`screen_off`×9、`screen_on`×9、`bucket_act|rare`、`aexit`×1，15 条补报。⚠️ **合并数"heartbeat 33 条"里有 28 条是它，不是米15**——把这条读反就会得出"她那边心跳一直正常"的假结论。
  - `diag.session` 四条的 `path`/`proj` **一条 `_` 都没有**（`s-s`/`r-o`/`o-r` + `live`/`stopped`），且三种终止原因同轮到手（`projection_stopped`＝共享期锁屏被系统撤授权 / `remote_bye` / `user_hangup`）⇒ 上一条那句"当前唯一可行的采法是她发起共享、Mi10 作观看端接"已经走完，那一格从 ⬜ 结清。
  - 同轮两条建立成功的会话都跨了一次真实换网（18:09:05 wifi→蜂窝、18:09:32 回 wifi），`liveMs=916267` 那通最后是**本端挂断**（viewer `user_hangup`）⇒ **换网杀信令、不杀媒体**在真机上第二次成立。
  - 本轮仍零读数：`stop_tap`（两次结束分别是本端挂断与系统撤授权，没人走主页/通知那两个入口——要它必须专门让**她**按一次"结束共享"）、`app_exit`（修法见上面 `app_exit` 条，0.2.64 之后才有读数）、`ice_failed`/`doze_on`/`video_resumed`/`surface_destroyed`/`backlog_drop`/`wake_fail`（都是"这一轮没发生那件事"，不是通路坏）。
  ⚠️ **认领机器的新办法：`deviceId` ≠ 物理机（10-19 靠它才发现生产上是"两台机器、三个身份"）**：把任意一条心跳的「服务器行时间 − `k=`」当作该设备内核 boot 的时刻戳，同 boot ⇒ 同物理机。本轮 `106db351` 与 `1a1d64ea` 算出来都是 `1791523055`（差 0s，并与本机 Mi10 `cat /proc/uptime` 反算的 boot 完全一致）⇒ 两台身份同在 Mi10 上（HyperOS 分身 + `Prefs.kt` 的 deviceId 存在 App 私有 SharedPreferences，卸载重装/分身各生成一个新 UUID）；`deba9443` 差 1103033s（12.8 天）⇒ 确是另一台。**后果**：AGENTS 那张"生产只有两台 deviceId"的表只在"每台各只有一个身份"时成立；判"某台手机掉线几次"要按物理机聚合，判"某次死是被杀还是被冻"必须按 deviceId 分开，而 `peer.gone A -> B` 里 B 是**哪个身份**还取决于服务器绑定指向谁（本轮 17:48:10 重新配对才把米15 的 peer 从 `106db351` 挪到 `1a1d64ea`，这正是 #61 陈旧绑定的现场样本）。
  ⚠️ **落盘时 `detail` 要过 `safeTag`**（非 `[\w.-]` 的字符一律换成 `_`，生产版同样如此）：下文写作 `sig=gone`、`afterMs=`、`downMs=` 的字段，在日志行里渲染成 `detail=sig_gone`、`afterMs_…`（10-18 拿生产 `[diag.event]` 实测：`detail=established_true_restarts_0`）。**按语义 grep，别按等号 grep**。
  **`peer_signal`（detail `sig=gone|bye`）是"秒断"的取证点**：`bye` 是对方真挂断，`peer.gone` 只代表对端**信令 socket** 没了（服务器 30s pong 超时即广播，见 §2.3 末），而媒体是 P2P 的、与信令死活无关。所以 `[diag.event] … event=peer_signal detail=sig=gone` 若带 `ice=CONNECTED`，就是"画面语音都还在、会话被一句信令离线拆掉"的直接证据；客户端当前**照旧**结束会话（是否改成 ICE 存活时宽限，等证据回来再定），上报必须排在拆除之前，否则 `ice` 只会是 `CLOSED`。
  `fg` + `capture_fail`/`fgs_start_failed` 是 Android 14+/HyperOS "后台起屏幕采集被拒"的判据。**信令掉线的断口长度读 `signaling_up`（detail `reconnectAttempt=… downMs=… n=<transport> d=<Doze>`）**：`signaling_down` 按定义发生在 socket 已断时，v0.2.58 之前它发不出去（只进本机 logcat），v0.2.59 起它会进补报队列、复活后带 `at=` 到达——**它的到达时刻是补报时刻，真实断口起点看 `at=`，也看它排在 `signaling_up` 之前还是之后**。复活那条现在自带网络与 Doze 现场（`n=`/`d=`，探针没起来时这两个字段整体不出现，不是 `n=unk`），一次复活就能同时回答"断了多久"和"当时是不是在 Doze/换了网"。**`downMs` 由 `SystemClock.elapsedRealtime()` 相减得出**（v0.2.59）：复活那一刻正是系统重新同步时钟的时刻，wall-clock 相减会把"断了 4 分钟"读成"断了 -3.8 小时"。（v0.2.57 校正的"down 永远收不到"到 0.2.59 已不成立，按版本读。）
  观看端黑屏的真判据是 **`surface_created` 之后有没有 `video_resumed`**（detail `afterMs=` 本端 surface 重建到首帧的毫秒数）：有=流回来了，黑屏属渲染；连续零帧只有 `video_stall`=媒体侧停了。**不要用 `sink_attached` 判黑屏**——它在 `onRemoteVideo` 里按 track 只发一次，surface 重建不会让它再出现，缺席证明不了任何事（v0.2.57 校正，10-17 双真机日志实锤）。
  `video_stall`（观看端连续零帧）有两种"零帧属正常"必须排除：① `screenoff`~`screenon` 之间（**对方**锁屏，画面暂停是设计）；② 本端 `surface_destroyed`~`surface_created` 之间（**自己**锁屏，解码没有渲染面必然停帧）。两者都不计数并清零 streak；门控挂在 surface 生命周期而非 `onPause`，否则小窗期间的真卡死会被一起放过。
  第三种更隐蔽：**帧率读不出（`x`，即 -1）不是 0 帧**。v0.2.62 之前观看端判据写的是 `inboundVideoFps <= 0.0`，而 `framesPerSecond` 在本版 libwebrtc 上根本不存在 ⇒ 每 10s 都在给 streak 加一格，30s 后必然告警——线上那 6 条 `event=video_stall ice=CONNECTED detail=rttMs_84/59/91` 就是这么来的（链路明明是好的）。现在帧率是区间相减的真读数，`< 0` 走「不判也不清零」分支，只有**测到 0.0** 才计数。
  第四种（10-19 本机第一次跑出 established 会话才见光，0.2.63 实测）：**共享端画面静止时的 0 帧是真的 0 帧，但画面没坏**。编码器在没有画面变化时不出新帧，观看端连续 3 个完整窗口测到 0.0 ⇒ 报一条 `video_stall`，而屏上仍是最后一帧、语音照走。实测设置页静置期间 viewer 在 14:46:17/14:47:47/14:54:09/14:56:13/14:58:45/14:59:57 各报一条（间隔 72~382s，**不刷屏**：`streak == VIEWER_STALL_POLLS` 是相等判断，一次静止期只发一条，帧回来才重新计数——这一点与 0.2.63 撤掉的 `b=fps0` 不同，那条每 10s 一条会灌满 P0 配额）。
  ⚠️ **但"一次静止期只发一条"这句在真机上比在模拟器上弱得多（10-19 双真机生产日志实测，#78）**：同一轮里观看端 `1a1d64ea` 在 17:50:48~18:11:52 报了 **9 条**，最短间隔 **40.0s**（18:02:50→18:03:30→18:04:10 连续三条都是 40s 整），最长 441s。原因是画面并非全程死静——通知、时钟秒针、MIUI 的任何一闪都会让帧回来一次，streak 清零后 30s（3 个窗口）就能再凑一条 ⇒ **"40s 一条"是真机静止画面的正常密度**，不是卡死。后果是配额而不是判据：一通 20 分钟的静态共享能吃掉 9 格，所以在 P0 里它会把死亡现场（`ice_failed`/`net_down`/`aexit`）挤出配额——#78 的处理是把它降级到 P1（与 `heartbeat` 合用 25 格），**不是**删掉这条埋点：档内仍按"保最新"淘汰，断连前最后一条 stall 一定活着，而那才是需要它的那一条；时刻线本来也能从 `qos_tick` 第 7 格复原。读旧日志时**绝不许把 `video_stall` 的数量当"卡了几次"**，要判卡死必须成对看共享端同刻 `qos_tick` 第 7 格（见下条）。
  ⚠️ **所以 `video_stall` 单独一条不判"画面死了"**，必须与**同一时刻共享端的 `qos_tick` 第 7 格**一起读（两行的服务器打印时刻来自同一台机器，可直接对齐；设备墙钟仍不可信）：
  - 共享端也 `0` ⇒ 源头没出帧 = 画面静止（或采集自己停了，配合 `proj`/`capture_ok` 再分），**不是**传输断；14:47:47 那对实测就是 `sharer q_5_s-s_83_960_x_78_**0**_0_6_0` + `viewer q_5_s-s_104_1600_x_78_**0**_0_6_0`。
  - 共享端 `>0` 而观看端 `0` ⇒ 帧出去了没回来，这才是媒体侧真断（解码/网络/选路）。
  - 共享端 `x` ⇒ 那一格没读数，**不构成**任何一种结论（不许把 `x` 当 0 用，这是坑㉑的原形）。
  这一差分是纯读日志的口径、不需要任何改动；把它做成客户端自动门控需要共享端把 `framesSent` 通过 DataChannel 告给观看端（新线协议），**先登记不改**。
- `diag.session` / `diag.event` **断连期不再静默丢弃**（v0.2.58）：`isConnected` 为假或 `send` 返回 false 时，事件写进本机 `filesDir/s2s-diag-backlog.ndjson`（日常入队只做 append 且**已挪到独立 io 线程**——广播接收器与 ICE 回调都可能落在主线程；上限 200 条在修剪/补报路径生效，另有一道 96KB 体积闸——超了才整表重写并截断），信令复活时按序补报（**v0.2.59 起一次复活连排 5 批 = 100 条**，此前每次只清 20 条，而抖动期每 17~104s 才重连一次，200 条积压要十次重连才出得来，那时现场早凉了）。
  **补报的已知盲区与 v0.2.59 的堵法（读日志前必须知道）**：`sendDiag` 在 `isConnected` 为真时是**乐观发送**——帧写进内核缓冲就算发出去了，没有 ack、不会抛异常。于是"网络刚丢、TCP 还没判死"那几十秒里发的待机读数会**静默消失**（10-18 双模拟器实测：客户端 logcat 有 `net_down detail=n=cell k=1052`，服务器 0 条，补报队列里也没有；23 秒后才被判死的 `signaling_down` 反而带着 `at_` 到了）。而"断网在前、信令死在后"这个顺序正是待机断连最要命的一段。所以现在每条**乐观发送过的**待机事件先在内存留一份副本（带事件时刻 `at=`，上限 12 条 / 180s 窗口），等断连被确认那一刻（`signaling_down` 的边沿）补进队列：复活后这批死亡现场会以 `detail=at_…` 再次到达。**到达次序不保证**——10-18 18:35 真机那轮里 `signaling_up`（实时）与三条补报落在**同一毫秒**且 `signaling_up` 先落盘，所以"补报一定在 `signaling_up` 之前"这种说法是错的，判据只能是各条自己的 `at=`（谁先发生）与 `k=`（单调差）。**同一事件出现"一条实时 + 一条 `at_`"是设计而不是 bug**（至少一次投递），按 `detail=at_` 前缀区分投递路径即可；`isConnected` 已为假时不入环（那条路径 `sendDiag` 本来就会入队，入环就是两条）。单调钟倒退（`elapsedRealtime` 归零=真重启）时环里的东西一律不补。
  ⚠️ **环只有 12 条 / 180s**，所以"补报"只回溯到 down 沿前 180 秒。10-18 18:35 真机那轮正好卡在边界上，可作为口径范例：`screen_on(k_796424, 18:31:18)` 距 down 沿 227s ⇒ **没被补报**；`screen_off(k_796479, 18:32:14)` 距 171s ⇒ 被补报。这不等于丢数据——没补报的那条**实时到达过**（服务器有它的 live 行）；真正会丢的是"实时没送达且又在环窗口外"，日志里无法区分这两种，所以**统计次数时不能把 live 与 `at_` 相加**（去重键：`event` + 去掉 `at_` 前缀后的 detail 正文，或直接数不同的 `k=`）。
  **堵法已在 10-18 14:42 双模拟器实测生效**（`svc wifi/data disable` 25s 后恢复）：服务器第一次收到 `event=net_down detail=at_1792305733486_n_cell_k_2626`，同批还有 `net_up`（`at_…758835`=断网后 25.3s，与人工恢复时刻吻合）与 `signaling_down`（`at_…766610`，比 `net_down` 晚 33s ⇒ 又一次实测到"断网在前、信令死在后"），四条按 `at=` 严格升序到达，复活那条 `signaling_up` 带 `downMs_3019`（单调钟）。对比同一轮修订前：`net_down` 在服务器上 0 条。
  **10-18 18:35 同一机制在真机 + 线上生产 server 上复现一次，并翻出一个新的顺序事实**（Mi10/HyperOS，adb `svc wifi disable`，蜂窝 NR 一直在场 ⇒ 这是一次**真的 wifi→蜂窝换网**，不是拔唯一网卡）：
  服务器首次收到 `net_type detail=at_1792319705420_from_wifi_to_cell_k_796650`（此前全天、含两台模拟器都是 0 条），同批 `net_down n_wifi k_796650`、`net_up n_cell k_796650`；死亡窗口里三条乐观发送过的待机读数 `screen_off(k_796479)`/`screen_on(k_796562)`/`heartbeat(k_796602 g_300)` 全部带 `at_` 补报回来；18:37:38 恢复 wifi 后**实时**（不带 `at_`）收到 `net_up n_wifi k_796803` + `net_type from_cell_to_wifi` ⇒ 换网的**正反两个方向在真机上都出过数**。复活那条 `signaling_up` 带 `downMs_3248`（单调钟 3.2s）。这一轮的 `net_down`/`net_up`/`net_type` 三条走的是补报路径而非实时，正是"换网瞬间乐观发送会丢"的实测。
  ⚠️ **顺序与模拟器那次相反**：这里 `signaling_down(at_…705147)` 比 `net_down(at_…705199)` **早 52ms**——socket 先死、ConnectivityManager 回调后到。所以"断网在前、信令死在后"只是**其中一种形态**（模拟器那种是拔掉唯一网卡、默认网络整体消失），换网（wifi↔蜂窝）时是**信令先死**。读日志时不许拿任一种顺序去否证另一类埋点，两种都要按"`net_*` 与 `signaling_*` 是否同批、`k=` 差多少"来判。另外这一轮的 `net_down`/`net_up`/`net_type` 三条都走的是**补报**路径（带 `at_`）而不是实时路径，说明换网瞬间乐观发送确实会丢——这正是留环的意义。
  **10-19 Mi10 又有两轮同向样本，把这句从"一次观测"升成"三次里三次同向"**：07:18 那轮 `signaling_down` 比 `net_down` 早 **54ms**、08:32 那轮早 **4.86s**（都是 wifi 掉→蜂窝 0.15s 起→2.7s 后 wifi 回来，`screenOn=false`，全部走补报）。⇒ 待机/换网场景里 **socket 层是比 `ConnectivityManager` 更早的传感器**；`net_*` 只能当晚到的旁证，不能当"死因的上级"。附带一条新读法：`at=` 与服务器到达时刻之差≈`downMs`（08:32：`at_…932324`→打印 08:32:26.489，`downMs_14174`），两者对得上说明这台设备的墙钟与服务器墙钟本次没漂——**但这不能假设**（Mi5 实测慢 ~10.5h），跨机对齐一律用 `k=`。
  ⚠️ **`screenOn` 列不是事件时刻的状态**：`diag.event`/`diag.session` 的这一列是**发送那一刻**的 `PowerManager.isInteractive`，而事件名来自广播 action，两者相隔可以是零点几秒。10-19 09:01:06.944 就有一条 `screen_off` 带 `screenOn=true`、0.73s 后紧跟 `screen_on`、两条 `k` 相同（=MIUI 造成的秒级闪灭）。判"那一刻屏到底灭没灭"要看事件序列本身（off→亚秒级 on→再 off），**不许拿这一列自证**；同理上一轮"18:35 那次 down 沿 `screenOn=true` 说明亮屏"这种读法只能算弱证据。
  **v0.2.59 起淘汰是分级而不是"保最新"**：规则是**默认 P0 保护，只有六个名字被降级**——`heartbeat`→P1（配额 25）、`video_stall`→P1（配额与心跳合用，理由见上文 #78 那条 ⚠️：真机静止画面下最短 40.0s 一条，重复那几条不携带新信息，而它的时刻线能从 `qos_tick` 第 7 格复原）、`qos_tick`→P2（配额 25），`echo_ack`→P1（0.2.65 交棒遥测，同样是**重复采样**：一条只带"最近一次 e / 本轮最大 x / miss m / 等待 p"四个标量且客户端已 5s 节流，p50/p90 由整条序列给出，丢掉其中几条不丢结论；它留在 P0 的代价是把死亡现场挤出配额），`aud_tick`+`aud_play`→P2（FR-16 内录两端的 10s 心跳，与 `qos_tick` 同一条口径：重复标量、不携带新信息，而"内录停了/没声了"由 P0 的 `aud_stopped` 说，不靠数 tick 的缺口；两端各留 P0 的话一通 20 分钟共享是 240 条，比配额本身还多），其余一切事件名（含 `signaling_down`/`signaling_up`/`screen_on`/`screen_off`/`probe_start`/`peer_signal`/`stop_tap`…）都是 P0（配额 150），`BacklogTiers.tierOf` 的 `else` 分支就是这条默认。**不要把它读成一份 P0 白名单**：白名单口径会让"新加的埋点"默认落进最廉价的一档、在断连积压时被静默丢掉（正是这套分级要避免的事），而 `signaling_down`/`screen_off` 这些名字本身也不在任何手写清单里；解析不出事件名的行按 P2 处理（它本来也重放不出去）。原来的 `takeLast(200)` 保最新会先挤掉**最旧**那几条，而断连积压里最旧的恰是死亡瞬间的现场。P0 自身超额被丢时会在队首插一条 `backlog_drop detail=dropped=<n>`——"队列满过"必须和"队列从没满过"在日志里长得不一样，否则静默会被读成"没发生"。补报**复用 `diag.event` 一种类型**（服务器零改动），真实时刻带在 `detail` 前缀 `at=<epochMs> ` 里——服务器行时间是"到达时刻"，不带 `at=` 就等于没报；会话终态降级成 `event=session_end`，`detail` 形如 `at=… why=<reason> dur=<liveMs>`（`[diag.session]` 行不打 detail，时刻会被丢掉）。grep 补报的口径是 `detail=at_`（`safeTag` 把 `at=` 的等号磨成 `_`，见上条警示；写 grep 前先拿一行真日志对形）。`app.diag` 不入队（它不是事件）。本端进程被杀且文件没写完时仍会丢，此时以服务端 `[ws] … close code=…` 为准。`diag.*` 只允许标量与枚举名，禁止携带任何业务内容（SDP/ICE 正文、文字、坐标）。
- 服务端日志行统一带 `YYYY-MM-DD HH:MM:SS.mmm` 本地时间前缀（v0.2.58 起含日期：这份文件跨天连续写，只有时分秒就分不清"07:23 的 close"是哪天；10-18 10:50 已随服务器上线，此前的老行仍是 `HH:MM:SS.mmm`，跨这个点读日志时按两种格式分别 grep）。呼叫与连接生命周期另有 `[call] kind=sdp|ice|bye …`、`[ws] … close code=… aliveFor=… iceSeen=… sessionMs=…`、`[ws] … pong 超时 terminate（假死）`，用于区分"主动挂断 / 假死被踢 / 被新连接顶掉"三种死法。逐帧噪声（`[relay]` 类型行与 `[call] sdp|ice`）在 `S2S_LOG=minimal` 时抑制，默认 `diag` 保持现行详细度；隐私名单不随档位放开：`paired`/`pair.code`/`sdp`/`ice`/`incoming`/`accepted`/`msg.*` 任何档位都只记类型（`server/src/logpolicy.js`）。⚠️ `sessionMs` 读作"本 socket 上的会话已进行多久"，**不是**"踢掉的那一刻还在通话中"——`ws.sessionAt` 在会话结束时不重置，会话结束后 socket 再活 8 分钟就会显示成 8 分钟前起算的时长（10-17 18:02 结束、18:10 pong 超时即被误读过一次）。判断"假死是否拆掉了活着的会话"请用客户端的 `peer_signal` 事件，别用 `sessionMs`。
  ⚠️ **`close code=1006` 里有一类不是断连，是"被自己顶掉的旧 socket 的死亡通知"（10-19 实测，数断连次数前必须先减掉）**：`index.js` 在鉴权后遇到同 `deviceId` 的新连接会打 `[ws] … replaced old connection (oldAliveFor=…ms)` 然后 `old.close()`；旧 socket 已经从 `online` 里摘掉但仍在 `wss.clients` 里被 30s 心跳轮询，于是它的 `close` 事件**必定在 +30.003s 后到达**。机器可查形式：`18:09:05.437 replaced(oldAliveFor=1254341ms)` → `18:09:35.440 close code=1006 aliveFor=1284344ms`（差正好 30003ms，`aliveFor = oldAliveFor + 30003`），且这类 1006 **前面没有** `pong 超时 terminate（假死）` 那行。本轮 27 条 1006 里有 2 条属于这一类、9 条前面有 pong 行。反过来说：**只有既没有 `replaced` 前导、也没有 `pong 超时` 的 1006，才是"客户端那边链路真的没了"**，而 `pong 超时` 那一类是服务器主动拆（假死判定，#55）。

### 2.4 语音留言文件（HTTP，与信令同端口）

- `POST /voice?deviceId=&token=` — 发件人上传，原始字节体（非 multipart），上限 1MB（超限 413）；鉴权同 hello（pairings token），成功回 `{id:UUID}`，落盘 `data/voice/<id>.m4a`。上传后须再发 `msg.post(kind:'voice', voiceId:id)` 才会入箱；留言被拒时服务端删除孤儿文件。
- `GET /voice/<id>?deviceId=&token=` — 仅**收件人本人**可下载（`MessageBook.hasVoice` 校验该语音确在其未读箱中），返回 `audio/mp4`；非收件人/错误 token 403，文件已删 404。
- 客户端上传的 token/deviceId 与 WebSocket hello 相同（Prefs 持久化的 pairToken）。

## 3. DataChannel 通道

共享端（offerer）创建四条通道（WebRtcClient，创建顺序 ctl → ctlg → aud → anno，旧观看端"后到者覆盖"最终仍持有 anno）：

| 通道 | label | 传输 | 载荷 | 用途 |
|---|---|---|---|---|
| anno | `anno` | 不可靠（可丢包） | JSON | 笔迹、表情、特效、锁屏状态 |
| ctl | `ctl` | 可靠有序 | JSON | 画质请求、被拒/错误通知等必须保序的信令 |
| ctlg | `ctlg` | 可靠无序 | JSON | 临时远程协助手势整笔（v0.2.40：消 ctl 队头阻塞） |
| aud | `aud` | 不可靠、不重传 | **二进制** | 设备内录音频帧（FR-16）：`[1B codec id][7B ADTS][AU]`，AAC-LC 48kHz 立体声 |

观看端按 label 分流；通道存在即握手（旧版对端无 ctl 通道 → 不开放临时协助模式）。所有 JSON 消息路由字段 `k`。
**`aud` 是唯一一条"存在≠可用"的通道**：它由共享端自己创建，所以对面认不认得它只能由对面亲口回 `audok` 证明
（见 §3.1）；观看端的 `onDataChannel` 因此必须逐个显式列出 label，**不许 else 兜底**——兜底会让任何新 label 顶掉
anno 并 `unregisterObserver()`，涂鸦随即静默失效（不崩、无日志、通道还在）。
画质请求优先走可靠 `ctl`；旧版对端没有 `ctl` 时回退到 `anno`，以保持跨版本兼容。
手势优先走 `ctlg`；旧版共享端没有 `ctlg` 时回退 `ctl`。授权"允许/收回"绝不能上无序通道（乱序=隐私事故）。
JSON 信令不许走 `aud`（`channelByLabel` 里 `aud` 显式返回 null）：把音频通道的背压传染给笔迹不是一句比喻。

### 3.1 k= 目录

观看端 → 共享端：

| k | 通道 | 载荷 | 说明 |
|---|---|---|---|
| `s` | anno | `id`(UUID), `c`(颜色索引) | 起笔 |
| `p` | anno | `id`, `x`, `y`（归一化 0–1，letterbox 视频矩形内） | 逐点 |
| `e` | anno | `id` | 收笔。**容忍丢包，收笔后 300ms 幂等重发一次**（共享端 endAt 只记一次） |
| `clear` | anno | — | 清空全部笔迹 |
| `res` | ctl（旧版回退 anno） | `edge`(960/1280/1600/1920) | 切换画质档；共享端弱网会按 RTT/丢包/带宽/FPS 自动逐档降级。**Android 14+ 这一档只改码率上限、不改采集分辨率**（改分辨率会触发系统撤销录屏授权，见 §2.3 `qos_tick` 第 4 格），13 及以下仍是真的重开采集 |
| `emoji` | anno | `e`(字符), `x`(0.15–0.85) | 屏幕飘表情 |
| `fx` | anno | `t`: `bomb`/`gift`/`rocket` | 满屏互动特效 |
| `g` | ctlg（旧版回退 ctl） | `pts`: [[xn,yn],…]（4 位小数截断），`dur`(ms) | 远程协助手势一笔一消息；共享端经无障碍 dispatchGesture 注入，仅在共享端明确允许本次协助时 |
| `audok` | ctl | `codec`(`aac`), `sr`(48000), `ch`(2) | **FR-16 新增**：观看端确认自己解得这路内录音频。只在**本端 `aud` 通道进入 OPEN 之后**发一次（通道没 OPEN 就 ack，对面会起一个帧送不出去的采集）。共享端收到才允许按开关起 `AudioPlaybackCapture`；参数逐项核对，任何一项对不上都按"不支持"处理。**这条是内录唯一的开闸依据**——`aud` 由共享端自己创建，通道非空证明不了对面的版本，所以不许用 `controlSupported` 那类"通道存在即支持"的口径推断它。旧观看端不会发这条，也不会认得 `aud` 通道以外的音频载荷（`onMessage` 起手就丢二进制）⇒ 对它们是纯惰性通道，不影响涂鸦 |

共享端 → 观看端：

| k | 通道 | 载荷 | 说明 |
|---|---|---|---|
| `screenoff` | anno + ctl（双通道补发） | — | 对方锁屏：画面暂停（冻在最后一帧）、操控授权收回、**会话与语音继续**（v0.2.56 起不再自动结束）；观看端据此挂横幅并暂停 `video_stall` 判定 |
| `screenon` | anno + ctl（双通道补发） | — | 对方解锁：撤横幅、恢复 `video_stall` 判定（操控授权不自动恢复，需重新点「允许一次远程协助」）。⚠️ v0.2.64 起**不再**是"画面回来了"的判据——见下面 `videoon` |
| `videooff` | anno + ctl（双通道补发） | — | **v0.2.64 新增**：系统撤销录屏授权（她锁屏省电、或她在系统隐私提示里点"停止录制"）⇒ **会话与语音继续，只有画面停了**。观看端挂「对方画面已暂停·语音继续」横幅并暂停 `video_stall` 判定；与 `screenoff` 分开是因为成因与出路都不同（`screenoff` 只需她解锁，`videooff` 必须她重新授权） |
| `videoon` | anno + ctl（双通道补发） | — | **v0.2.64 新增**：重开授权成功、画面续流（同一视频轨、同一 PeerConnection，不重新协商、不重新 ICE）。它是收掉 `videooff` 横幅的**唯一**入口 |
| `ctl_denied` | ctl | `reason`(可选: `screenoff`) | 手势被拒（未授权/收回/无障碍缺失），观看端横幅说明原因；共享端 4s 节流 |
| `a` | anno | `id`(与 `s`/`p`/`e` 同一 UUID) | **v0.2.65 新增**：共享端已把这一笔**落墨**（0.2.65 起笔迹在她那边缓冲到收笔才画，见 §3.2「为什么共享端缓冲到收笔」）。观看端收到即把本地副本交棒淡出（150ms）；没收到则最多白留 600ms 后兜底淡出（`HANDOVER_FALLBACK_MS`，取 600 是为了升级期"新观看端+旧共享端"的重叠窗口按常量本身与 0.2.64 同量级）。故意走 **anno 而不是 ctl**：它既是交棒信号，也是 `echo_ack` 的测量对象——要量的就是"笔迹经过的那条队列"的排队+往返，走可靠通道会测到另一条队列、得出"很快"的假读数。旧版观看端不认这个 `k` ⇒ 落进 `SignalRouter` 的 `else`，静默忽略、无害；因此**不允许**任何代码假设"对端一定会回 ack" |
| `aud` | ctl | `on`(0/1), `state`(`live`/`silent`), `playback`(1) | **FR-16 新增**：共享端报来它自己那台手机的内录状况。`on`=他的开关意图，`state`=采集线程实际读到没有声音。**只有两态**，不是"live/silent/blocked"：`AudioPlaybackCapture` 读到全零 PCM 本身不携带结论（可能是那个 App 声明了不可录制，也可能当时本来就没声音在放），所以这一路不制造第三个态，`silent` 的文案必须把两种成因与出路一起说清。`playback=1` 声明共享端能消费观看端的 `audplay` 播放故障反馈；缺失/未知按旧端处理，观看端不得发送该扩展消息。⚠️ **`on` 只认 0/1**：缺失或别的值一律按"没读数"处理（`DataAction.None`），不许把猜出来的 `false` 报成"TA 没开"。⚠️ **沿触发+去重**（`publishAudStatus` 按 `开关\|状态` 签名叫一次）：它是 ctl 上的一条 JSON，每帧一报会把有序信令队列当音频道用——而 `aud` 帧丢掉的那些时刻信令反而在排队。旧观看端不认这个 `k` ⇒ 落进 `SignalRouter` 的 `else`，静默无害 |
| `audplay` | ctl，未 OPEN 时兼容回退 anno | `failed`(0/1) | 新版观看端→声明 `playback=1` 且当前 `aud` OPEN 的共享端，稀疏发送播放器失败/恢复沿。旧共享端不会声明能力，观看端不发，避免旧端把未知控制消息交给涂鸦层。共享端显式消费该消息，只更新对端播放提示，不改本机采集状态 |

### 3.2 为什么共享端把笔迹缓冲到收笔才落墨（重影的物理根因，0.2.65）

**她的屏幕就是镜头。** Android 没有任何"按窗口把某一层从采集中排除"的能力：`MediaProjection`
录的是合成后的整块显示表面，我们的悬浮窗只要画出来了就在视频里；`FLAG_SECURE` 不是"挖掉这块"，
而是让它变成一个**黑块**（AGENTS 已登记的 MIUI 坑）。所以"从采集里删掉我们的 UI"这条路是关着的，
唯一可行的原则是：**我们的 chrome 不进被采集的画面；笔迹只留一份真值。**

重影的机制因此是结构性的，不是时序bug：观看端本地 0 延迟渲染一条，笔点同时经 anno（丢包通道）
到达共享端、**逐点立刻渲染** ⇒ 被采集 ⇒ 回灌观看端。观看端于是同时看到"手指当前的真位置"和
"视频里落后 250ms~1.5s 的副本"，读成一条跟手的蛇尾双影。把它调成"猜一个回显时长再淡本地副本"
永远赌不赢（回显时长随网络与 ROM 波动），所以 0.2.65 起改成两份真值只留一份：

- **共享端**：`s`/`p` 只进 `SharerStrokeBuffer` 攒点、**不上屏**；`e`（或 1200ms 静默兜底）才提交成
  一条笔迹去画。书写期间视频里根本没有副本 ⇒ 观看端那条是唯一的线。
- **观看端**：本地副本收笔后**保持满 alpha 不动**，直到共享端的 `k="a"` 到达才用 150ms 淡掉；
  ack 丢了（anno 本来就容丢包）最多 600ms 兜底淡出，绝不永久驻留。
  150ms 刻意短于任何合理回显链路 ⇒ "两条同时可见"的窗口压到肉眼读不成重影。
- **代价要说清（这是产品口径，不是实现细节）**：她**不再实时看你正在画的中间过程**，只看到成品。
  你这边永远 0 延迟，所以你画笔的延迟没有变大；她等的是"收笔"这一下。
- **升级期（一端新一端旧）的口径**：
  ① 新观看端 + 旧共享端（≤0.2.64 不回 ack，也不缓冲）⇒ 本地线只能靠 600ms 兜底淡掉，
  与回显重叠最长 600+150=**750ms**，与 0.2.64 的 600ms **同量级**（形状不同：旧版从收笔就线性淡，
  新版前 600ms 保持满 alpha）。这是 `HANDOVER_FALLBACK_MS` 从 1500 降到 600 的全部理由——
  保留 1500 时它是旧版的 2.5 倍，而这一格**本机拿不到逐帧实测**（见下）。
  ② 旧观看端 + 新共享端 ⇒ 旧版仍按自己的 600ms 淡出，而成品要等采集→编码→解码那一段
  （`echo_ack` 只量 anno 往返，不含视频管线），观感是"我画的线闪一下就没了，过一会儿才出现"。
  ⚠️ ①② 都是**按常量与日志推出来的界，不是实测**：`exec-out screencap` 单机 0.6~1.5s/帧、
  双模拟器视频约 0.5fps，150ms 级与"哪一帧视频带墨"在这台宿主上结构性不可测（`docs/AUDIT-MATRIX.md` §1.1 保持 `⬜`）。

配套的双铅笔（入口球）同理：观看端的球是 Activity 内的 View，本来就不进采集；共享端的球是
`TYPE_APPLICATION_OVERLAY` 窗口，只要可见就进视频 ⇒ 闲置 alpha 从 0.15 改成 **0**（`View.setAlpha(0f)`
仍参与触摸命中，命中区没缩小），入口换成摇一摇 / 通知栏动作 / 那块隐形命中区三选。
`emoji`/`fx` 的"本地 + 回显两份"是**故意的正反馈**（两副动画各自随机位置、不叠在同一条线上），
不改，登记见 `docs/AUDIT-MATRIX.md` §1。

## 4. 会话时序（正常路径）

```
配对:  A pair.request → pair.code(客户端同时渲染二维码 soul2soul://pair?code=xxx)
                                    B 手输/扫码 → pair.enter(code) → 双方 paired(token)
找回:  A pair.recall → B pair.recall_ask → B pair.recall_ok(accept) → 双方 paired(新token)
互摇:  双端各发 pair.shake（3s 窗口内）→ 双方 paired(新token)
呼叫:  B invite → A incoming(from, iceServers)
接听:  A accept → B accepted(iceServers) → B 启动 ScreenShareService
协商:  B sdp(offer) → A sdp(answer) → ice×N 双向
媒体:  anno/ctl/ctlg DataChannel
结束:  任一侧 bye（或异常断线 → 服务端补 peer.gone；观看端未接听时 bye/cancel 均为“迟到回声”，被 SessionActivity 忽略）
```

## 5. 已知漂移与勘误

- **SPEC.md §3 标注协议表过时**：早期设计为 `stroke.start / stroke.p / stroke.end / anno.clear`（且通道为 reliable+ordered），线上实际是 anno 走**不可靠**通道、字段 `k:"s"/"p"/"e"/"clear"`（另有 emoji/fx/res 等扩展）。以本文 §3.1 为准。
- **服务端常量配置**：repo 代码默认 `PAIR_CODE_TTL=10min / ENTER 尝试上限=5`；生产经 `.env` 注入 `PAIR_CODE_TTL_MS=1800000 / PAIR_ENTER_MAX_ATTEMPTS=10`（`4ce2928` 起 hotfix 已回灌为 env 可配，源码不再漂移）。单测锁定的是 repo 默认行为；部署时仍需人工核对线上 env 值（见根目录 AGENTS.md）。
- **离线留言（msg.*、HTTP /voice）**：生产服务器已于 2026-10-06 部署；旧客户端（≤v0.2.23）收到新信号只是忽略（unknown type 静默丢弃），留言功能需 v0.2.24 客户端。
