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
| `pair.failed` | `reason`: `too_fast` / `too_many_attempts` / `code_invalid` / `self_pair` | 配对各类失败 |
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
- `diag.session` 字段（**会话终止原因**，v0.2.55）：`role`(sharer/viewer), `reason`(`connect_TIMEOUT` / `ice_DISCONNECTED_TIMEOUT` / `ice_FAILED` / `capture_START_FAILED` / `projection_stopped` / `sdp_*` / `webrtc_start_failed` / `local_stop` / `user_hangup` / `remote_bye` / `local_close` …), `ice`(末次 ICE 态), `setupMs`(发起→连通), `liveMs`(连通→结束), `fg`(本端是否有 Activity 在前台), `screenOn`, `app`(版本名)，v0.2.59 起另带 `path`(末次选路缩码，同 `qos_tick` 的两字母语法)与 `proj`(采集侧状态 `starting`/`live`/`stopped`，观看端为 `-`)。服务端只落 `[diag.session]`、不转发。
  **`path`/`proj` 的三态读法（10-18 实测，别把"没有值"读成"没埋"）**：`_`=客户端这条线根本没发该字段（0.2.58 及以前恒为 `_`，即服务器白印了两天）；`-`=发了但当时确实无值；`live`/`h-s` 等=真读数。10-18 双模拟器两条 0.2.59 的 `diag.session` 实形为 `role=sharer reason=local_close ice=FAILED path=- proj=live` 与 `role=viewer reason=connect_TIMEOUT ice=FAILED path=- proj=-`——`ice=FAILED` 的会话从没选路过，`path=-` 是正当答案不是缺陷；观看端不采集，`proj=-` 同理。**`path` 想要真值必须等一次 established 的会话**（模拟器因 `.env` 里 TURN 是占位值而结构性打不通，所以这一格仍属未采样）。
  这两列此前服务器印了两天全是 `_`：字段在服务端一直存在，但客户端从没 `put` 过（`safeTag(undefined)` = `"_"`）。**`_` 是"没埋"，`-` 是"埋了但这一侧没有这个东西"**，读日志时这两个必须分开——把 `_` 读成"走的是未知网络路径"会得出相反结论。**v0.2.58 起 `setupMs=-1` 表示从未连通**，`liveMs` 停在失去连通那一刻——此前 `connectedSinceMs` 会被 ICE 闪断清零，导致"聊了 18.9 分钟被拆"与"根本没连上"在日志里同形（0.2.55/0.2.56 生产日志的 liveMs 全部失真）。
  `screen_off_timeout` 随 v0.2.56 锁屏保活一起废除（锁屏不再结束会话，因此不会再产生该原因）。
  为什么需要：WebRTC 是媒体直连，服务器只转发 `sdp/ice`，看不见采集失败、启动失败、谁按的挂断。此前"米15 发起 4~6 秒即断"在服务器日志里只能看到一对没有原因的 bye。
- `diag.event` 字段：`role`, `event`（会话期 `screen_off`/`screen_on`/`capture_ok`/`capture_fail`/`ice_outage`/`ice_recovered`/`ice_failed`/`video_stall`/`video_resumed`/`viewer_paused`/`viewer_resumed`/`surface_created`/`surface_size`/`surface_destroyed`/`sink_attached`/`fgs_start_failed`/`webrtc_start_failed`/`leave_hint_screen_off`/`pip_enter`/`pip_exit`/`peer_signal`/`gather_done`/`qos_tick`/`qos_bad`；信令期 `signaling_down`/`signaling_up`/`app_exit`；待机期（v0.2.59 新增）`probe_start`/`heartbeat`/`net_up`/`net_down`/`net_type`/`doze_on`/`doze_off`/`bucket_*`/`wake_ok`/`wake_off`/`wake_fail`/`aexit`；补报队列自身 `session_end`/`backlog_drop`）, `ice`, `detail`(≤64字符), `fg`, `screenOn`, `app`。服务端只落 `[diag.event]`、不转发。
  **`role` 有三种**：`sharer`/`viewer` 是会话中的角色，`standby` 是**非会话期**（v0.2.59）。读日志时把 `role=standby` 当成"观看端"会得出完全错误的结论——待机掉线和观看端掉线是两件事。
  ⚠️ **所有 `diag.*` 都只在 socket 已鉴权（`hello` 带对了 pair token）之后才会被服务器处理**，未配对期间的遥测在服务器上必然为空。这条不是 bug 也不是埋点坏了：没鉴权的 diag 帧会被服务端直接丢弃。所以"米15 装了 0.2.59 却一条待机事件都没有"的第一因是**她还没配对**，先去看 `diag.version code=59` 有没有 `paired`。
  ⚠️ **`bucket_*` 的名字来自 `UsageStatsManager.getAppStandbyBucket()`**：`bucket_act`(10)/`bucket_wset`(20)/`bucket_freq`(30)/`bucket_rare`(40)/`bucket_rst`(**45**)。档位值不许凭记忆改——t0 直接拿编译 SDK 的 `android.jar` 跑 `javap -constants` 对表（RESTRICTED 曾被按记忆写成 50，而平台里 50 根本不存在，真机掉进 restricted 就会被读成"未知档"，恰好丢掉米15 待机断网最想要的那一格）。落在五档之外的值渲染成 `bucket_x<数>`，那是"ROM 给了个没见过的档"，不是埋点坏了。HyperOS 待机断网几乎都发生在掉到 `bucket_rst` 那一刻，detail 带 `prev=<上一档>`。**10-18 用 `am set-standby-bucket` 逼出一整条降级链**：`bucket_rare(prev=10)`→`bucket_rst(prev=40)`→`bucket_wset(prev=45)`→`bucket_act(prev=20)`——`prev=` 与档名两两自洽，且 `bucket_rst` 能出数就是 45 那格修对的直接证据（按记忆写成 50 的那版只会打成 `bucket_x45`）。注意**模拟器不会自然掉档**（锁屏静置 8 分钟里 `b=10` 恒 ACT），所以"HyperOS 自己会不会降到 rst"仍未采样。
  ⚠️ **10-18 Mi10 真机（HyperOS，Android 12）第一次出数就是 `bucket_x5 detail=prev_-1`**——不是埋点坏了，是 ROM 给了个 AOSP 五档之外的数。拿系统侧对表：`dumpsys usagestats` 里 `STANDBY_BUCKET_CHANGED package=com.soul2soul.app standbyBucket=5 reason=d`（10-17 21:28 起），全机分布 `578×10 / 369×45 / 169×40 / 61×5 / 38×20 / 28×30`，另有 `com.miui.voiceassist` 在 `standbyBucket=50`。结论：MIUI 自己造了 `5` 和 `50` 两个档，`x5`/`x50` 是**忠实读数**；t0 的 javap 守卫只能校验 AOSP 那五个号，越界值必须保持原样透出、不许悄悄归并到最近的档（归并就等于把"ROM 私有省电档"这一格抹掉，而那正是米15 断网最可能的凶手）。读日志时看到 `bucket_x5` 应当判为"掉进 MIUI 未知档"，不是"读数失败"。
  ⚠️ **`heartbeat` 的判据是"缺口"不是"到达"**：只在**已配对且不在会话中**时 300s 一条，detail 形如 `k=123456,g=300,n=wifi,d=0,b=10,w=1,s=1`（`k`=elapsedRealtime 秒=单调锚点，`g`=与上一条心跳的单调秒差、`x`=本进程首条，`n`=transport 代号 wifi/cell/vpn/eth/bt/unk，`d`=Doze，`b`=bucket，`w`=保活锁，`s`=信令在否）。连续两条 `k=` 差 >600s ⇒ 中间进程被冻结或被杀。设备时钟不可信（Mi5 实测慢 ~10.5h），**只用 `k=` 算间隔，绝不拿服务器行时间或 `at=` 相减**。日志侧口径：整格渲染成 `k_123456_g_300_n_wifi_d_0_b_10_w_1_s_1`（等号与逗号都被 `safeTag` 磨成 `_`），grep 时按 `k_` 后面的数字取锚点。**10-18 实测「配对·锁屏待机」这一格终于出数了**：`screen_off(k_3032)` 后静置 8 分钟，中途 `heartbeat detail=k_3309_g_x_n_cell_d_0_b_10_w_0_s_1` 准点到达（`k` 比 `probe_start` 那条的 3009 正好 +300 单调秒；`g=x`=本进程首条心跳，不是缺数），唤醒时 `screen_on(k_3512)`。这条是"待机期客户端一句话都不说"这个原始病灶的直接反证；**缺口判据（两条 `k=` 差 >600s）仍待一次真被冻/被杀的采样**。
  ⚠️ **`qos_tick` 每 10s 一条（仅会话 established 期间），detail 是定位语法不是 `k=v`**：`q=<rtt>,<选路>,<可用kbps>,<长边>,<视频丢包×10>,<视频抖动>,<视频帧率>,<音频丢包×10>,<音频抖动>,<音频消隐>`，例 `q=29,h-s,1200,1598,5,21,29,0,3,0`。选路两字母 `h`=host `s`=srflx `r`=relay `o`=其它/未知（`h-s` 即本地 host 对远端 srflx = 局域网直连）；`x`=这一格没有读数，**不许当 0 读**（0 是"确实没丢包"，`x` 是"没测到"）。各项上限：rtt/抖动/消隐 999ms、可用带宽 99999kbps、边长 9999px、帧率 999fps、丢包 100.0%（=`pct×10` 的 1000）——**超出即夹**，读到 999 只能判"≥999"。夹这么狠是因为 `detail` 只有 ~47 可用字符（64 减去补报前缀 `at=<13位毫秒> `），被 `safeTag` 从尾部静默裁掉就什么都读不出来了。日志侧口径：`q_29_h-s_1200_1598_5_21_29_0_3_0`——逗号也被磨成 `_`，所以读的时候按 `_` 切位，而选路码 `h-s` 里的短横保留（`-` 在 `safeTag` 白名单里）。
  **`qos_bad`（异常立即插一条，不等下一个 10s）detail 以 `b=` 开头标原因**，一个 tick 最多一条，顺序即严重度：`b=fps0`（本端收流 0 帧，且**不是**自己停帧/对方锁屏）、`b=pth,<旧>><新>`（选路变化，`unknown` 变化不报）、`b=vl,<×10>`/`b=al,<×10>`（视频/音频丢包 ≥5.0%）、`b=rtt,<旧>><新>`（≥3 倍**且**基线 ≥60ms）。
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
  **`r=10 i=125` = HyperOS/MIUI 的省电杀进程指纹（10-19 在 Mi10 上双侧互证，这是本项目第一次拿到"待机为什么断"的确定答案）**：生产日志里 `106db351` 的心跳从 18:29 到 21:04 **连续 33 条准点 `+300` 单调秒**（全程 `screenOn=false w_1 s_1 b_5 d_0`＝锁屏、锁在、socket 在、档没动），21:07:09.990 socket `close code=1006 aliveFor=9121611ms`（2.5h 好连接被外部切断，**不是** pong 假死），之后 10 小时零读数；07:03 拉起后首次连通即报 `aexit detail=r_10_i_125_a_596`，而 `596 分钟前`正是 21:07。设备侧独立核对：`dumpsys activity exit-info com.soul2soul.app` 的 #0 = `timestamp=2026-10-18 21:07:09.676 reason=10 (USER REQUESTED) importance=125`，`events` buffer 同一时刻有 `am_kill … due to AutoPowerKill`。**读法**：一次"待机后联系不上"的正确判据是 **最后一条 `heartbeat` 的时刻 + 复活时那条 `aexit` 的 `a=`**，两者对上就是"被杀"；只看 socket `code=1006` 会把它误读成网络断。注意**别把这套结论搬到米15**：她 22:10/22:37 两次是连上 2.5~6.9 秒就 `1006`，那是另一种签名，且 `r=` 的真值必须由她那台设备自己报出来（计划里猜的 `SIGNALED`/`LOW_MEMORY`/`FREEZER` 已被 Mi10 的实际答案否证）。两条口径顺带钉死：① 本 ROM 的 `dumpsys activity exits` 子命令**不存在**（`Unknown command: exits`），要看 AEI 只能用 `dumpsys activity exit-info <pkg>`，也就是说**没有我们这条埋点就根本读不到**；② `ApplicationExitInfo.description`（这里写着 `stop com.soul2soul.app due to AutoPowerKill`）**客户端仍然不读**（隐私线），上面这句是 ROM 侧 dump 出来的对照、不是 App 上报的。
  ⚠️ **复活窗口有一格可能是真缺陷、但目前只是注入场景**：06:59 在 `mWakefulness=Dozing` 下 `am start` 拉起 App，进程活着（`S2S-Main: update check: remote=59 local=59` 说明 HTTPS 出得去）、`Presence.ensureStarted()` 也调了，但 AMS 侧记录是 `app=null createdFromFg=false startForegroundCount=0` ⇒ **FGS 启动被后台限制静默拒掉，进程"在线"却永远连不上信令，而且这种状态下连埋点都发不出来**；把屏幕真正点亮并解锁后重新 `am start`，同一服务立刻 `uidState: FGS / startForegroundCount=1` 并在 07:03:18 注册成功。这不等于她手机上会发生（她要点亮屏幕才能操作），所以只登记为**待复现的假在线死路**，不许写成已证实的根因。
  **`ice_failed` 与 `ice_outage` 必须在日志里分得开**：`ice_outage`（DISCONNECTED）会等恢复、`ice_failed` 是打洞彻底失败（detail 带 `established=`、`restarts=`、`path=` 的缩码）。取证必须排在恢复策略判定之前，否则 `established` 已被改掉。
  下面这一行是**机器读的**：`DiagEventRegistryTest` 把它与 main 源码扫出的事件名逐一比对，两个方向都不许有差（新增事件名不写进来，t1 直接红；写了没在发，也直接红）。以 `_` 结尾的是前缀项——`bucket_` 覆盖 `bucket_act`/`bucket_wset`/`bucket_freq`/`bucket_rare`/`bucket_rst`。上面那些散文段落是会腐烂的，这行不会。
  <!-- DIAG-EVENT-NAMES: screen_off/screen_on/capture_ok/capture_fail/ice_outage/ice_recovered/ice_failed/video_stall/video_resumed/viewer_paused/viewer_resumed/surface_created/surface_size/surface_destroyed/sink_attached/fgs_start_failed/webrtc_start_failed/leave_hint_screen_off/pip_enter/pip_exit/peer_signal/gather_done/qos_tick/qos_bad/signaling_down/signaling_up/app_exit/probe_start/heartbeat/net_up/net_down/net_type/doze_on/doze_off/bucket_/wake_ok/wake_off/wake_fail/aexit/session_end/backlog_drop -->
  `probe_start` 是探针存活性的对照点：只有 `probe_start` 而没有后续任何边沿事件 = 注册成功但状态一直没翻转（或进程被冻），与"探针根本没起来"是两种情况。
  ⚠️ **`wake_ok`/`wake_off` 曾经 0 条，根因是接线顺序而不是通路（10-18）**：`PresenceService` 里 `updateWakeLock()` 排在 `StandbyProbe.start()` 之前，首次 acquire 的回报落在探针 `running=false` 上被直接丢弃——而"待机时保活锁到底拿到没有"正是待机断连要查的第一件事，等于白埋。改成**先起探针再拿锁**（解绑侧反过来：先放锁再停探针，否则 `wake_off` 同样发不出去）后，15:00 双模拟器装上即出 `event=wake_ok detail=k_3732`，两台各一条（一条直发、一条经补报带 `at_`，两条投递路径同时见光）。顺序已锁进 `StandbyTelemetryTest` S8：换回去即红（已实测）。**同一条修复也让心跳里的 `w=` 从撒谎变成真**：修复前 14:50~14:59 四条 `heartbeat` 全是 `w_0` 而锁其实拿着（`lastWakeHeld` 从没被喂过），修复后 15:05:40 `k_4032 … w_1 s_1`、15:05:43 `k_3958 … w_1 s_1` 两台同时翻正——读旧版本的 `w_0` 时**不许**当成"ROM 把锁放了"，那是埋点顺序缺陷，0.2.59 装机后的版本才有可信的 `w=`。
  **10-18 本机 dev server 的出数清单（米15 装 0.2.59 之前，这就是证据的唯一形态；都是模拟器，不许读成"真机已验"）**：已出数 `probe_start`/`heartbeat`/`screen_on`/`screen_off`/`net_up`/`net_down`/`doze_on`/`doze_off`（`cmd deviceidle` 逼出）/`bucket_act|wset|freq|rare|rst`（`am set-standby-bucket` 逼出）/`aexit`/`wake_ok`/`signaling_up`/`signaling_down`/`ice_failed`/`detail=at_` 补报；**仍零采样且原因各异**——`qos_tick`/`qos_bad`（要 established 的媒体会话，本机 `.env` 的 TURN 是占位值、模拟器 ICE 结构性打不通）、`gather_done`（当时和上面归为同一条原因，**10-19 复查更正为埋点缺陷**：同轮 `ice_failed` 已出数而它 0 条，因为发射点只挂在 `onIceGatheringChange(COMPLETE)` 上，快速 FAILED 的会话根本走不到收集完成 ⇒ 已在收尾补一条 `inc=1` 的发射点，S9 锁定）、`net_type`（要一次真实换网且前后 transport 代号不同）、`wake_off`（要走到放锁那条边）、`app_exit`（要解绑或停服务，而 10-18 试过 `am stopservice` 被 FGS 直接回 `Error stopping service`，只能等真机点解绑）、`backlog_drop`（要 P0 积压超 150 条）。零采样不等于埋点坏了，但也**不许**当成"已验证"。
  **10-18 18:24~18:39 Mi10 真机 + 线上生产 server 的出数清单**（`repro-capture.sh` 副本 `~/s2s-repro/20261018-182228`，deviceId `106db351-…`，`app=0.2.59`）：已出数 `probe_start`/`bucket_x5`/`wake_ok`/`net_up`/`net_down`/`net_type`（**正反两向**，实测细节见下文 18:35 那条）/`aexit`/`screen_on`/`screen_off`（非会话期，含 MIUI 自己点亮屏幕那两次）/`heartbeat`×3（`k_796302 g_x`→`k_796602 g_300`→`k_796903 g_301`，全程 `w_1 s_1 b_5 d_0`）/`signaling_down`/`signaling_up`/`detail=at_` 补报。**真机侧仍零采样**：`doze_on`/`doze_off` —— **结构性**，Mi10 在 deep-Doze 白名单里（`dumpsys deviceidle whitelist` → `user,com.soul2soul.app,10442`，电池优化=不限制），AOSP Doze 的网络限制对它根本不生效；这一格只能米15 出，且**米15 有没有被同样加白正是待查项**（她在 App 里点过"不限制"= 加白 ⇒ 若仍断连则 Doze 不是凶手；没点过 ⇒ `doze_on` 会先出现）。另 `wake_off`/`wake_fail`/`app_exit`（要她侧解绑或进程真被杀）、`backlog_drop`（要 P0>150）、`qos_tick`/`qos_bad` 与 `diag.session` 的真实 `path`/`proj`（`gather_done` 已从这一组摘出去：收尾那条 `inc=1` 补上之后，只要会话真的发起过连接就会出数、不再要求 established，见上面 `gather_done` 条；这一组仍然都要一次 established 媒体会话，而**该会话的任一端跑 0.2.59 就够出那一端的读数**——米15 仍是 58（0.2.59 于 10-19 06:52:41 上通道，她打开 App 才会检更），所以当前唯一可行的采法是"她发起共享、Mi10 作观看端接"，那时 Mi10 的 `diag.session path=` 应立即从 `_` 变成两字母码、`proj=-`；她装上 59 之后两端都能出）。
  ⚠️ **落盘时 `detail` 要过 `safeTag`**（非 `[\w.-]` 的字符一律换成 `_`，生产版同样如此）：下文写作 `sig=gone`、`afterMs=`、`downMs=` 的字段，在日志行里渲染成 `detail=sig_gone`、`afterMs_…`（10-18 拿生产 `[diag.event]` 实测：`detail=established_true_restarts_0`）。**按语义 grep，别按等号 grep**。
  **`peer_signal`（detail `sig=gone|bye`）是"秒断"的取证点**：`bye` 是对方真挂断，`peer.gone` 只代表对端**信令 socket** 没了（服务器 30s pong 超时即广播，见 §2.3 末），而媒体是 P2P 的、与信令死活无关。所以 `[diag.event] … event=peer_signal detail=sig=gone` 若带 `ice=CONNECTED`，就是"画面语音都还在、会话被一句信令离线拆掉"的直接证据；客户端当前**照旧**结束会话（是否改成 ICE 存活时宽限，等证据回来再定），上报必须排在拆除之前，否则 `ice` 只会是 `CLOSED`。
  `fg` + `capture_fail`/`fgs_start_failed` 是 Android 14+/HyperOS "后台起屏幕采集被拒"的判据。**信令掉线的断口长度读 `signaling_up`（detail `reconnectAttempt=… downMs=… n=<transport> d=<Doze>`）**：`signaling_down` 按定义发生在 socket 已断时，v0.2.58 之前它发不出去（只进本机 logcat），v0.2.59 起它会进补报队列、复活后带 `at=` 到达——**它的到达时刻是补报时刻，真实断口起点看 `at=`，也看它排在 `signaling_up` 之前还是之后**。复活那条现在自带网络与 Doze 现场（`n=`/`d=`，探针没起来时这两个字段整体不出现，不是 `n=unk`），一次复活就能同时回答"断了多久"和"当时是不是在 Doze/换了网"。**`downMs` 由 `SystemClock.elapsedRealtime()` 相减得出**（v0.2.59）：复活那一刻正是系统重新同步时钟的时刻，wall-clock 相减会把"断了 4 分钟"读成"断了 -3.8 小时"。（v0.2.57 校正的"down 永远收不到"到 0.2.59 已不成立，按版本读。）
  观看端黑屏的真判据是 **`surface_created` 之后有没有 `video_resumed`**（detail `afterMs=` 本端 surface 重建到首帧的毫秒数）：有=流回来了，黑屏属渲染；连续零帧只有 `video_stall`=媒体侧停了。**不要用 `sink_attached` 判黑屏**——它在 `onRemoteVideo` 里按 track 只发一次，surface 重建不会让它再出现，缺席证明不了任何事（v0.2.57 校正，10-17 双真机日志实锤）。
  `video_stall`（观看端连续零帧）有两种"零帧属正常"必须排除：① `screenoff`~`screenon` 之间（**对方**锁屏，画面暂停是设计）；② 本端 `surface_destroyed`~`surface_created` 之间（**自己**锁屏，解码没有渲染面必然停帧）。两者都不计数并清零 streak；门控挂在 surface 生命周期而非 `onPause`，否则小窗期间的真卡死会被一起放过。
- `diag.session` / `diag.event` **断连期不再静默丢弃**（v0.2.58）：`isConnected` 为假或 `send` 返回 false 时，事件写进本机 `filesDir/s2s-diag-backlog.ndjson`（日常入队只做 append 且**已挪到独立 io 线程**——广播接收器与 ICE 回调都可能落在主线程；上限 200 条在修剪/补报路径生效，另有一道 96KB 体积闸——超了才整表重写并截断），信令复活时按序补报（**v0.2.59 起一次复活连排 5 批 = 100 条**，此前每次只清 20 条，而抖动期每 17~104s 才重连一次，200 条积压要十次重连才出得来，那时现场早凉了）。
  **补报的已知盲区与 v0.2.59 的堵法（读日志前必须知道）**：`sendDiag` 在 `isConnected` 为真时是**乐观发送**——帧写进内核缓冲就算发出去了，没有 ack、不会抛异常。于是"网络刚丢、TCP 还没判死"那几十秒里发的待机读数会**静默消失**（10-18 双模拟器实测：客户端 logcat 有 `net_down detail=n=cell k=1052`，服务器 0 条，补报队列里也没有；23 秒后才被判死的 `signaling_down` 反而带着 `at_` 到了）。而"断网在前、信令死在后"这个顺序正是待机断连最要命的一段。所以现在每条**乐观发送过的**待机事件先在内存留一份副本（带事件时刻 `at=`，上限 12 条 / 180s 窗口），等断连被确认那一刻（`signaling_down` 的边沿）补进队列：复活后这批死亡现场会以 `detail=at_…` 再次到达。**到达次序不保证**——10-18 18:35 真机那轮里 `signaling_up`（实时）与三条补报落在**同一毫秒**且 `signaling_up` 先落盘，所以"补报一定在 `signaling_up` 之前"这种说法是错的，判据只能是各条自己的 `at=`（谁先发生）与 `k=`（单调差）。**同一事件出现"一条实时 + 一条 `at_`"是设计而不是 bug**（至少一次投递），按 `detail=at_` 前缀区分投递路径即可；`isConnected` 已为假时不入环（那条路径 `sendDiag` 本来就会入队，入环就是两条）。单调钟倒退（`elapsedRealtime` 归零=真重启）时环里的东西一律不补。
  ⚠️ **环只有 12 条 / 180s**，所以"补报"只回溯到 down 沿前 180 秒。10-18 18:35 真机那轮正好卡在边界上，可作为口径范例：`screen_on(k_796424, 18:31:18)` 距 down 沿 227s ⇒ **没被补报**；`screen_off(k_796479, 18:32:14)` 距 171s ⇒ 被补报。这不等于丢数据——没补报的那条**实时到达过**（服务器有它的 live 行）；真正会丢的是"实时没送达且又在环窗口外"，日志里无法区分这两种，所以**统计次数时不能把 live 与 `at_` 相加**（去重键：`event` + 去掉 `at_` 前缀后的 detail 正文，或直接数不同的 `k=`）。
  **堵法已在 10-18 14:42 双模拟器实测生效**（`svc wifi/data disable` 25s 后恢复）：服务器第一次收到 `event=net_down detail=at_1792305733486_n_cell_k_2626`，同批还有 `net_up`（`at_…758835`=断网后 25.3s，与人工恢复时刻吻合）与 `signaling_down`（`at_…766610`，比 `net_down` 晚 33s ⇒ 又一次实测到"断网在前、信令死在后"），四条按 `at=` 严格升序到达，复活那条 `signaling_up` 带 `downMs_3019`（单调钟）。对比同一轮修订前：`net_down` 在服务器上 0 条。
  **10-18 18:35 同一机制在真机 + 线上生产 server 上复现一次，并翻出一个新的顺序事实**（Mi10/HyperOS，adb `svc wifi disable`，蜂窝 NR 一直在场 ⇒ 这是一次**真的 wifi→蜂窝换网**，不是拔唯一网卡）：
  服务器首次收到 `net_type detail=at_1792319705420_from_wifi_to_cell_k_796650`（此前全天、含两台模拟器都是 0 条），同批 `net_down n_wifi k_796650`、`net_up n_cell k_796650`；死亡窗口里三条乐观发送过的待机读数 `screen_off(k_796479)`/`screen_on(k_796562)`/`heartbeat(k_796602 g_300)` 全部带 `at_` 补报回来；18:37:38 恢复 wifi 后**实时**（不带 `at_`）收到 `net_up n_wifi k_796803` + `net_type from_cell_to_wifi` ⇒ 换网的**正反两个方向在真机上都出过数**。复活那条 `signaling_up` 带 `downMs_3248`（单调钟 3.2s）。这一轮的 `net_down`/`net_up`/`net_type` 三条走的是补报路径而非实时，正是"换网瞬间乐观发送会丢"的实测。
  ⚠️ **顺序与模拟器那次相反**：这里 `signaling_down(at_…705147)` 比 `net_down(at_…705199)` **早 52ms**——socket 先死、ConnectivityManager 回调后到。所以"断网在前、信令死在后"只是**其中一种形态**（模拟器那种是拔掉唯一网卡、默认网络整体消失），换网（wifi↔蜂窝）时是**信令先死**。读日志时不许拿任一种顺序去否证另一类埋点，两种都要按"`net_*` 与 `signaling_*` 是否同批、`k=` 差多少"来判。另外这一轮的 `net_down`/`net_up`/`net_type` 三条都走的是**补报**路径（带 `at_`）而不是实时路径，说明换网瞬间乐观发送确实会丢——这正是留环的意义。
  **v0.2.59 起淘汰是分级而不是"保最新"**：P0（`aexit`/`session_end`/`ice_*`/`net_*`/`doze_*`/`bucket_*`/`wake_fail`/`qos_bad`/`app_exit`/`gather_done`/`backlog_drop`）配额 150、P1 `heartbeat` 25、P2 `qos_tick` 25。原来的 `takeLast(200)` 保最新会先挤掉**最旧**那几条，而断连积压里最旧的恰是死亡瞬间的现场。P0 自身超额被丢时会在队首插一条 `backlog_drop detail=dropped=<n>`——"队列满过"必须和"队列从没满过"在日志里长得不一样，否则静默会被读成"没发生"。补报**复用 `diag.event` 一种类型**（服务器零改动），真实时刻带在 `detail` 前缀 `at=<epochMs> ` 里——服务器行时间是"到达时刻"，不带 `at=` 就等于没报；会话终态降级成 `event=session_end`，`detail` 形如 `at=… why=<reason> dur=<liveMs>`（`[diag.session]` 行不打 detail，时刻会被丢掉）。grep 补报的口径是 `detail=at_`（`safeTag` 把 `at=` 的等号磨成 `_`，见上条警示；写 grep 前先拿一行真日志对形）。`app.diag` 不入队（它不是事件）。本端进程被杀且文件没写完时仍会丢，此时以服务端 `[ws] … close code=…` 为准。`diag.*` 只允许标量与枚举名，禁止携带任何业务内容（SDP/ICE 正文、文字、坐标）。
- 服务端日志行统一带 `YYYY-MM-DD HH:MM:SS.mmm` 本地时间前缀（v0.2.58 起含日期：这份文件跨天连续写，只有时分秒就分不清"07:23 的 close"是哪天；10-18 10:50 已随服务器上线，此前的老行仍是 `HH:MM:SS.mmm`，跨这个点读日志时按两种格式分别 grep）。呼叫与连接生命周期另有 `[call] kind=sdp|ice|bye …`、`[ws] … close code=… aliveFor=… iceSeen=… sessionMs=…`、`[ws] … pong 超时 terminate（假死）`，用于区分"主动挂断 / 假死被踢 / 被新连接顶掉"三种死法。逐帧噪声（`[relay]` 类型行与 `[call] sdp|ice`）在 `S2S_LOG=minimal` 时抑制，默认 `diag` 保持现行详细度；隐私名单不随档位放开：`paired`/`pair.code`/`sdp`/`ice`/`incoming`/`accepted`/`msg.*` 任何档位都只记类型（`server/src/logpolicy.js`）。⚠️ `sessionMs` 读作"本 socket 上的会话已进行多久"，**不是**"踢掉的那一刻还在通话中"——`ws.sessionAt` 在会话结束时不重置，会话结束后 socket 再活 8 分钟就会显示成 8 分钟前起算的时长（10-17 18:02 结束、18:10 pong 超时即被误读过一次）。判断"假死是否拆掉了活着的会话"请用客户端的 `peer_signal` 事件，别用 `sessionMs`。

### 2.4 语音留言文件（HTTP，与信令同端口）

- `POST /voice?deviceId=&token=` — 发件人上传，原始字节体（非 multipart），上限 1MB（超限 413）；鉴权同 hello（pairings token），成功回 `{id:UUID}`，落盘 `data/voice/<id>.m4a`。上传后须再发 `msg.post(kind:'voice', voiceId:id)` 才会入箱；留言被拒时服务端删除孤儿文件。
- `GET /voice/<id>?deviceId=&token=` — 仅**收件人本人**可下载（`MessageBook.hasVoice` 校验该语音确在其未读箱中），返回 `audio/mp4`；非收件人/错误 token 403，文件已删 404。
- 客户端上传的 token/deviceId 与 WebSocket hello 相同（Prefs 持久化的 pairToken）。

## 3. DataChannel 三通道

共享端（offerer）创建三条通道（WebRtcClient，创建顺序 ctl → ctlg → anno，旧观看端"后到者覆盖"最终仍持有 anno）：

| 通道 | label | 传输 | 用途 |
|---|---|---|---|
| anno | `anno` | 不可靠（可丢包） | 笔迹、表情、特效、锁屏状态 |
| ctl | `ctl` | 可靠有序 | 画质请求、被拒/错误通知等必须保序的信令 |
| ctlg | `ctlg` | 可靠无序 | 临时远程协助手势整笔（v0.2.40：消 ctl 队头阻塞） |

观看端按 label 分流；通道存在即握手（旧版对端无 ctl 通道 → 不开放临时协助模式）。所有消息为 JSON，路由字段 `k`。
画质请求优先走可靠 `ctl`；旧版对端没有 `ctl` 时回退到 `anno`，以保持跨版本兼容。
手势优先走 `ctlg`；旧版共享端没有 `ctlg` 时回退 `ctl`。授权"允许/收回"绝不能上无序通道（乱序=隐私事故）。

### 3.1 k= 目录

观看端 → 共享端：

| k | 通道 | 载荷 | 说明 |
|---|---|---|---|
| `s` | anno | `id`(UUID), `c`(颜色索引) | 起笔 |
| `p` | anno | `id`, `x`, `y`（归一化 0–1，letterbox 视频矩形内） | 逐点 |
| `e` | anno | `id` | 收笔。**容忍丢包，收笔后 300ms 幂等重发一次**（共享端 endAt 只记一次） |
| `clear` | anno | — | 清空全部笔迹 |
| `res` | ctl（旧版回退 anno） | `edge`(960/1280/1600/1920) | 切换采集长边（清晰度）；共享端弱网会按 RTT/丢包/带宽/FPS 自动逐档降级 |
| `emoji` | anno | `e`(字符), `x`(0.15–0.85) | 屏幕飘表情 |
| `fx` | anno | `t`: `bomb`/`gift`/`rocket` | 满屏互动特效 |
| `g` | ctlg（旧版回退 ctl） | `pts`: [[xn,yn],…]（4 位小数截断），`dur`(ms) | 临时远程协助手势一笔一消息；共享端经无障碍 dispatchGesture 注入，仅在共享端明确允许本次协助时 |

共享端 → 观看端：

| k | 通道 | 载荷 | 说明 |
|---|---|---|---|
| `screenoff` | anno + ctl（双通道补发） | — | 对方锁屏：画面暂停（冻在最后一帧）、操控授权收回、**会话与语音继续**（v0.2.56 起不再自动结束）；观看端据此挂横幅并暂停 `video_stall` 判定 |
| `screenon` | anno + ctl（双通道补发） | — | 对方解锁：撤横幅、恢复 `video_stall` 判定（操控授权不自动恢复，需重新点「允许一次远程协助」） |
| `ctl_denied` | ctl | `reason`(可选: `screenoff`) | 手势被拒（未授权/收回/无障碍缺失），观看端横幅说明原因；共享端 4s 节流 |

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
