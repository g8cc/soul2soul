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
| `pair.enter` | `code` | 已 hello | 输入对方配对码。每连接最多尝试 5 次（防爆破，超限回 `too_many_attempts`）；码过期/不存在回 `code_invalid`；输自己的码回 `self_pair`。 |
| `invite` | — | 已鉴权 | 呼叫对端。限速 2s/次。对端不在线回 `peer.offline`。 |
| `accept` | — | 已鉴权 | 接听。 |
| `decline` | — | 已鉴权 | 拒接。 |
| `cancel` | — | 已鉴权 | 主叫在对方接听前撤呼。 |
| `bye` | — | 已鉴权 | 挂断，原样转发给对端。 |
| `sdp` | `sdp:{type, sdp}` | 已鉴权 | 原样转发给对端（日志省略正文）。 |
| `ice` | `candidate:{...}` | 已鉴权 | 原样转发给对端（日志省略正文）。 |
| `unpair` | — | 已鉴权 | 解除配对：删除双向关系并**双向清空留言箱**（文字+语音文件），在线端立即收 `unpaired`。 |
| `msg.post` | `kind:"text"`+`text`(≤500字) 或 `kind:"voice"`+`voiceId`(UUID)+`durMs`(0<d≤60000) | 已鉴权+已配对 | 给对端留言（对方离线也收）。发件限速 1 条/秒；被拒回 `msg.failed`。语音文件先经 HTTP `POST /voice` 上传（见 2.4）。 |
| `msg.read` | `ids`[] | 已鉴权 | 阅后即删：从**自己**的留言箱删除这些 id（含语音文件），只能删自己的。 |
| `app.diag` | 见 2.3 | 已 hello | 服务端只落日志 `[diag.selfcheck]`，不转发。 |
| `app.version` | 见 2.3 | 已 hello | 服务端只落日志 `[diag.version]`，不转发。 |

未 hello 之前的一切消息被忽略；未鉴权（`authed=false`）时 `invite` 回 `peer.offline:not_authed`，其余媒体/呼叫类消息静默丢弃。

### 2.2 服务端 → 客户端

| type | 载荷 | 触发 |
|---|---|---|
| `registered` | `paired`(bool), `peerOnline`(bool) | hello 成功后 |
| `peer.online` | — | 对端上线（本端已配对时） |
| `error` | `reason`: `bad_device_id` / `bad_token` | hello 校验失败；`bad_token` 后紧跟 close 4001 |
| `pair.code` | `code`(6位数字串) | pair.request 成功 |
| `pair.failed` | `reason`: `too_fast` / `too_many_attempts` / `code_invalid` / `self_pair` | 配对各类失败 |
| `paired` | `token`(32hex), `peerOnline` | 配对成功，双方各发一条；token 由客户端持久化用于后续 hello |
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

留言条目结构：`{id:UUID, from, kind:'text'|'voice', text | voiceId+durMs, ts, expires}`。
容量与隐私：每收件人最多 20 条（溢出丢最旧，语音文件同删）、TTL 7 天自动销毁、`msg.read` 送达即删（阅后即删）；无厂商推送，**打开 App 才送达**。决策逻辑在 `server/src/messages.js#MessageBook`（纯逻辑，单测覆盖）。

### 2.3 ICE 凭证与遥测

- `iceServers` 由服务端签发（`server/src/turn.js#buildIceServers`）：`username = floor(now/1000)+ttlSec`（默认 ttl 3600s），`credential = base64(HMAC-SHA1(secret, username))`（reverse-proxy 限时凭证）。含 1 条 `stun:` + 2 条 `turn:`（udp/tcp）。
- `app.diag` 字段：`role`(viewer/sharer), `model`, `os`, `sdk`, `app`, `notif`, `overlay`, `acc`, `mic`, `battery`，外加扩展位（观看端 `ctlSupported`、共享端 `ctlAllowed`）。
- `app.version` 字段：`localCode`, `localName`, `remoteCode`, `remoteName`, `hasUpdate`。

### 2.4 语音留言文件（HTTP，与信令同端口）

- `POST /voice?deviceId=&token=` — 发件人上传，原始字节体（非 multipart），上限 1MB（超限 413）；鉴权同 hello（pairings token），成功回 `{id:UUID}`，落盘 `data/voice/<id>.m4a`。上传后须再发 `msg.post(kind:'voice', voiceId:id)` 才会入箱；留言被拒时服务端删除孤儿文件。
- `GET /voice/<id>?deviceId=&token=` — 仅**收件人本人**可下载（`MessageBook.hasVoice` 校验该语音确在其未读箱中），返回 `audio/mp4`；非收件人/错误 token 403，文件已删 404。
- 客户端上传的 token/deviceId 与 WebSocket hello 相同（Prefs 持久化的 pairToken）。

## 3. DataChannel 双通道

共享端（观看端→共享端）创建两条通道（WebRtcClient）：

| 通道 | label | 传输 | 用途 |
|---|---|---|---|
| anno | `anno` | 不可靠（可丢包） | 笔迹、表情、特效、清晰度、锁屏状态 |
| ctl | `ctl` | 可靠 | 操控手势整笔、被拒通知 |

观看端按 label 分流；通道存在即握手（旧版对端无 ctl 通道 → 禁入操控模式）。所有消息为 JSON，路由字段 `k`。

### 3.1 k= 目录

观看端 → 共享端：

| k | 通道 | 载荷 | 说明 |
|---|---|---|---|
| `s` | anno | `id`(UUID), `c`(颜色索引) | 起笔 |
| `p` | anno | `id`, `x`, `y`（归一化 0–1，letterbox 视频矩形内） | 逐点 |
| `e` | anno | `id` | 收笔。**容忍丢包，收笔后 300ms 幂等重发一次**（共享端 endAt 只记一次） |
| `clear` | anno | — | 清空全部笔迹 |
| `res` | anno | `edge`(1280/1920) | 切换采集长边（清晰度） |
| `emoji` | anno | `e`(字符), `x`(0.15–0.85) | 屏幕飘表情 |
| `fx` | anno | `t`: `bomb`/`gift`/`rocket` | 满屏互动特效 |
| `g` | ctl | `pts`: [[xn,yn],…]（4 位小数截断），`dur`(ms) | 操控手势一笔一消息；共享端经无障碍 dispatchGesture 注入，仅在「允许TA操控」时 |
| `doodle` | ctl | — | 「喊TA画」：远程开/关共享端本机自画悬浮层（省掉摇一摇）；观看端仅在 ctl 通道存在时可点 |

共享端 → 观看端：

| k | 通道 | 载荷 | 说明 |
|---|---|---|---|
| `screenoff` | anno | — | 对方锁屏（画面冻结，15s 后自动结束） |
| `screenon` | anno | — | 对方解锁 |
| `ctl_denied` | ctl | `reason`(可选: `screenoff`) | 手势被拒（未授权/收回/无障碍缺失），观看端横幅说明原因；共享端 4s 节流 |

## 4. 会话时序（正常路径）

```
配对:  A pair.request → pair.code ─┐
                                   B pair.enter(code) → 双方 paired(token)
呼叫:  B invite → A incoming(from, iceServers)
接听:  A accept → B accepted(iceServers) → B 启动 ScreenShareService
协商:  B sdp(offer) → A sdp(answer) → ice×N 双向
媒体:  anno/ctl DataChannel
结束:  任一侧 bye（或异常断线 → 服务端补 peer.gone；观看端未接听时 bye/cancel 均为“迟到回声”，被 SessionActivity 忽略）
```

## 5. 已知漂移与勘误

- **SPEC.md §3 标注协议表过时**：早期设计为 `stroke.start / stroke.p / stroke.end / anno.clear`（且通道为 reliable+ordered），线上实际是 anno 走**不可靠**通道、字段 `k:"s"/"p"/"e"/"clear"`（另有 emoji/fx/res 等扩展）。以本文 §3.1 为准。
- **服务端常量配置**：repo 代码默认 `PAIR_CODE_TTL=10min / ENTER 尝试上限=5`；生产经 `.env` 注入 `PAIR_CODE_TTL_MS=1800000 / PAIR_ENTER_MAX_ATTEMPTS=10`（`4ce2928` 起 hotfix 已回灌为 env 可配，源码不再漂移）。单测锁定的是 repo 默认行为；部署时仍需人工核对线上 env 值（见根目录 AGENTS.md）。
- **离线留言（msg.*、HTTP /voice）**：生产服务器已于 2026-10-06 部署；旧客户端（≤v0.2.23）收到新信号只是忽略（unknown type 静默丢弃），留言功能需 v0.2.24 客户端。
