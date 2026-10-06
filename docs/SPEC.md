# Soul2Soul 技术规格（SPEC）

版本 v0.1 ｜ 2026-09-28 ｜ 关联: `PRD.md`（需求）、`DESIGN.md`（架构与选型记录）
本文档是**实现的合同**：协议、状态机、坐标公式、时序、验收映射都以此为准。

---

## 1. 仓库结构

```
soul2soul/
├── docs/                     # PRD / SPEC / DESIGN / OPEN-QUESTIONS / MORNING-REPORT
├── server/                   # 信令服务器 (Node.js + ws) + TURN(docker compose)
│   ├── src/index.js
│   └── turn/docker-compose.yml
└── app/                      # Android (Kotlin, minSdk 24)
    ├── settings.gradle.kts / build.gradle.kts / gradle.properties
    └── app/src/main/
        ├── AndroidManifest.xml
        ├── java/com/soul2soul/app/
        │   ├── App.kt                        # Application: EglBase + PeerConnectionFactory 初始化
        │   ├── MainActivity.kt               # 配对 + 首页（发起共享入口）
        │   ├── signaling/SignalingClient.kt  # OkHttp WebSocket 封装
        │   ├── signaling/SignalBus.kt        # 进程内事件分发（SharedFlow）
        │   ├── webrtc/WebRtcClient.kt        # PeerConnection 生命周期 / 轨道 / DataChannel
        │   ├── session/PresenceService.kt    # 常驻待命前台服务（持有信令长连接）
        │   ├── session/ScreenShareService.kt # 共享端 FGS（mediaProjection|microphone）
        │   ├── session/AnnotationOverlayService.kt # 共享端悬浮窗
        │   ├── session/OverlayCanvasView.kt  # 共享端笔迹渲染层（不可触摸）
        │   ├── session/DrawingOverlayView.kt # 观看端画笔输入层（叠加在渲染器上）
        │   └── session/SessionActivity.kt    # 观看端：来电/观看/挂断
        └── res/                              # 布局/图标/文案
```

进程模型：
- `PresenceService`（FGS `dataSync`，低优先级通知）——**常驻**，唯一持有 SignalingClient。
- `ScreenShareService`（FGS `mediaProjection|microphone`）——会话期存在，持有共享端 `WebRtcClient`。
- `AnnotationOverlayService`（普通 Service）——会话期存在，持有悬浮窗。
- 观看端的 `WebRtcClient` 由 `SessionActivity` 持有（观看端始终在前台）。
- 服务与 UI 之间通过 `SignalBus`（信令消息）与静态单例（WebRTC 客户端引用）协作，v0 接受这种简单模型。

## 2. 信令协议 v1（与 `server/src/index.js` 实现一一对应）

传输：WebSocket，JSON 编码，UTF-8。连接后**必须先发 `hello`**，否则其他消息被忽略。
服务器只把消息转发给「已配对的另一台设备」，不落任何会话内容。

| type | 方向 | 字段 | 说明 |
|---|---|---|---|
| `hello` | C→S | deviceId | 连接报到；顶替同 id 旧连接 |
| `registered` | S→C | paired:bool, peerOnline:bool | hello 应答 |
| `pair.request` | C→S | — | 申请配对码 |
| `pair.code` | S→C | code(6位) | 10 分钟有效 |
| `pair.enter` | C→S | code | 用对方给的码完成配对 |
| `pair.failed` | S→C | reason: code_invalid/self_pair | |
| `paired` | S→C | peerOnline:bool | 双方都收到 |
| `peer.online` | S→C | — | 对方连上信令时通知，用于点亮「对方在线」 |
| `invite` | C→S | — | 发起呼叫 |
| `incoming` | S→C(被叫) | iceServers[] | 对方呼叫你 |
| `peer.offline` | S→C(主叫) | reason? | 对方不在线/未配对 |
| `accept` | C→S(被叫) | — | 接听 |
| `accepted` | S→C(主叫) | iceServers[] | 对方接了 |
| `decline` / `declined` | C→S / S→C | — | 拒接 |
| `unpair` / `unpaired` | C→S / S→C | — | 解除配对：删除双向关系，双方收到 unpaired 后回到未配对态 |
| `sdp` | 双向 | sdp:{type,sdp} | 原样转发 |
| `ice` | 双向 | candidate:{candidate,sdpMid,sdpMLineIndex} | 原样转发 |
| `bye` | 双向 | — | 挂断 |
| `peer.gone` | S→C | — | 对方连接断开（服务器代发） |
| `cancel` / `call.canceled` | C→S / S→C(对方) | — | 主叫在对方接听前撤呼 |
| `app.diag` | C→S | role/model/os/sdk/app/notif/overlay/acc/mic/battery… | 会话能力自检矩阵，服务器只落 `[diag.selfcheck]` 日志、不转发 |
| `app.version` | C→S | localCode/localName/remoteCode/remoteName/hasUpdate | 更新判定自检，服务器只落 `[diag.version]` 日志、不转发 |

> 完整字段/限速/鉴权规则见 [`docs/PROTOCOL.md`](PROTOCOL.md)（以代码为准的线上协议参考）。

`iceServers` 格式：`[{urls:[...]}, {urls:[...], username, credential}]`，
TURN 凭证 = `HMAC-SHA1(secret, "<unix秒过期>")`，1 小时时限（coturn `use-auth-secret`）。

客户端地址配置：`app/build.gradle.kts` 里 `SIGNALING_URL` 默认指向云服务器，
模拟器联调用 `./gradlew assembleDebug -PS2S_SIGNALING_URL=ws://10.0.2.2:8080` 覆盖。

### 2.1 呼叫建立时序

```
主叫A(共享)                    服务器                     被叫B(观看)
   │ invite ──────────────────► │ ── incoming+iceServers ──► │
   │                            │                            │ [全屏来电UI, 30s超时]
   │                            │ ◄──────── accept ────────  │
   │ ◄── accepted+iceServers ── │                            │ [创建 PeerConnection 等offer]
   │ [系统屏幕录制授权弹窗]        │                            │
   │ [FGS启动 → getMediaProjection]                           │
   │ [创建pc+轨道 → offer]       │                            │
   │ ── sdp(offer) ───────────► │ ──────── sdp(offer) ─────► │
   │                            │ ◄── sdp(answer) + ice* ──  │
   │ ◄── sdp(answer) + ice* ─── │                            │
   │            ICE 连通 → DataChannel 开 → Live              │
```

挂断：任一端 `bye` → 对方收到后释放全部资源；服务器检测到连接断开则代发 `peer.gone`。

## 3. 标注协议（DataChannel "anno"，reliable + ordered）

> **勘误（2026-10）**：本节是 v1 设计稿，与线上实现不符。实际为：anno 通道**不可靠**（丢包不重传，收笔信号 300ms 幂等重发），另有可靠的 `ctl` 通道承载操控手势与被拒通知；消息以 `k:"s"/"p"/"e"/"clear"/"res"/"emoji"/"fx"/"g"…` 路由。**线上格式一律以 [`docs/PROTOCOL.md`](PROTOCOL.md) §3 为准。**

| 消息 | 字段 | 说明 |
|---|---|---|
| `stroke.start` | id:String, c:Int(0-2 颜色) | 手指按下 |
| `stroke.p` | id, x:Float, y:Float | 移动点，坐标 ∈ [0,1]，相对**视频帧矩形** |
| `stroke.end` | id | 抬手 |
| `anno.clear` | — | 清除当前笔迹（v1.1） |

渲染规则：
- 观看端：`stroke.*` 由本端手势直接驱动，**本地立即绘制**（零延迟）；激光笔 2000ms 线性淡出后移除。
- 共享端：收到 `stroke.*` → 悬浮层绘制，同样 2000ms 淡出 → 笔迹被 MediaProjection 采集进视频流（回声与本地笔迹短暂重叠，激光模式下不可察觉，见 DESIGN §5.2）。

## 4. 客户端状态机

```
Idle ──invite──► Calling ──accepted──► Preparing(授权+FGS) ──pc建立──► Connecting
 ▲                 │30s超时/offline        │授权拒绝/异常                   │ICE连通
 │                 ▼                       ▼                              ▼
 └── bye/gone/失败 ◄──────────────────────────────► Ended ◄──────────── Live
```

- `Live` 中 `peer.gone` / `bye` / ICE `FAILED` → 直接 `Ended`。
- 断线宽限：ICE `DISCONNECTED` 容忍 10s（libwebrtc 自带重连窗口），超时视为失败。
- 共享端锁屏：VirtualDisplay 停止出帧 → 30s 无帧定时器触发 `Ended`（FR-8）。

## 5. 坐标映射（FR-5 的精度保证）

观看端（触点 → 归一化）：
```
videoRect = aspectFit(videoW×videoH, 容器W×H)     # letterbox 后视频实际显示矩形
xn = (touchX - videoRect.left) / videoRect.width
yn = (touchY - videoRect.top)  / videoRect.height
```
共享端的**远程操控**（归一化 → 无障碍注入）使用共享手机的整块物理显示尺寸：
`px = xn × realDisplayW`，`py = yn × realDisplayH`。`realDisplayW/H` 必须来自
`Display.getRealSize()`/`getRealMetrics()`，不能来自 `currentWindowMetrics.bounds` 或
`resources.displayMetrics`（后两者可能扣掉状态栏/导航栏，只代表应用窗口；这会让所有纵坐标
系统性偏到上方）。`dispatchGesture` 的原点就是整屏左上角，因此不再额外加状态栏 inset。

共享端的**悬浮标注**也以同一整屏矩形为视口；包围盒窗口通过
`FLAG_LAYOUT_IN_SCREEN` 保持 `x/y` 与物理屏幕原点一致。采集帧只按长边缩放并保持整屏宽高比，
所以手势和标注使用同一组归一化坐标，不需要再按包围盒窗口宽高比缩放。
正确性依据：视频帧内容 = 共享端屏幕内容，归一化坐标在两侧指向同一语义位置，与两端分辨率/比例无关。
`videoW×videoH` 来自观看端收到的首帧（VideoSink onFrame 上报）。

## 6. Android 关键实现规格

1. **屏幕授权与 FGS 顺序（Android 14+ 强制）**：
   `createScreenCaptureIntent()` → onActivityResult → `startForegroundService(ScreenShareService)` → `onStartCommand` 内**先 `startForeground()`** → 再构造 `ScreenCapturerAndroid(resultData, callback)`。
2. **采集参数**：`isScreencast=true` 的 VideoSource；长边 ≤1280、宽高取偶、30fps；`ScreenCapturerAndroid` 自适应旋转。
3. **音频**：`JavaAudioDeviceModule` 默认实现；`AudioManager.mode = IN_COMMUNICATION` + speakerphone on；libwebrtc 自带 AEC3/NS/AGC。
4. **悬浮窗**（共享端）：
   - 层1 笔迹：`TYPE_APPLICATION_OVERLAY`，`FLAG_NOT_TOUCHABLE|NOT_FOCUSABLE|LAYOUT_NO_LIMITS`，透明全屏，不干扰游戏。
   - 层2 气泡：同类型，`FLAG_NOT_FOCUSABLE`（保留触摸），可拖动，含「结束」。
   - 权限：`Settings.canDrawOverlays()` 检查 + 跳转授权（Android 10+ 无自动授予）。
5. **重连**：信令 WebSocket 断开后 3s 定时重连（PresenceService 常驻保证）；媒体层交给 ICE。
6. **锁屏看门狗（FR-8）**：监听 `ACTION_SCREEN_OFF/ON` 广播；锁屏 30 秒后自动结束会话并通知对端。
   ⚠️ 不能用「帧数停止增长」判断锁屏——静止画面同样不出帧，会误杀正常会话（已踩坑并修复）。
7. **通知**：
   - 待命：低优先级常驻「在线待命」；
   - 来电：`CATEGORY_CALL` 高优先级 heads-up + 全屏意图（锁屏也可弹）；App 在前台时由 PresenceService 直接拉起 SessionActivity（不依赖全屏意图权限，Android 14 对侧载 App 有收紧）；
   - 会话：常驻「正在共享屏幕」+ 结束 action。
8. **测试钩子**：`SessionActivity` 支持 `autoAccept` extra（E2E 免点击接听），仅自动化用，不影响真人交互。
9. **构建变体**：`prod`（默认，连云服务器）/ `emu`（applicationId 加 `.emu` 后缀，连 `ws://10.0.2.2:8080` 供双模拟器联调）。不要用 `-P` 属性切换 URL——BuildConfig 任务对属性变化不敏感会拿旧值（已踩坑）。
10. **Java 层 VideoFrame 引用计数铁律**：`VideoTrack.addSink` 的 Java Sink **绝不能调 `frame.release()`**（分发器统一释放；多放一次 = native SIGABRT，已两次踩坑）。要观测帧，包装 `CapturerObserver` 透传计数。

## 7. 服务器部署规格

- 组件与端口：见 `server/README.md`（8080/TCP 信令；3478/UDP+TCP、49160-49200/UDP TURN）。
- 环境变量：`TURN_STATIC_AUTH_SECRET`（两端一致）、`TURN_HOST`（公网 IP）。
- 进程守护：pm2（`pm2 start src/index.js --name soul2soul`）或 nohup 起步。
- 升级 wss：Caddy/Nginx TLS 反代 443→8080，客户端 `SIGNALING_URL` 改 `wss://域名`，协议零改动。
- 配对数据：`server/data/pairings.json`（原子写：tmp+rename）。

## 8. 测试计划

**T1 信令冒烟（无需手机）**
`wscat -c ws://IP:8080` 两开，模拟 hello/pair/invite/accept/sdp 透传/bye，断开触发 peer.gone。

**T2 双真机主流程（对应 PRD AC-1~4）**
1. 两机安装 → A 申请配对码 → B 输入 → 双方显示已配对
2. A 点「一起玩」→ B 全屏来电 → B 接受
3. A 授权屏幕 → 切消消乐 → B 看到画面与语音互通
4. B 在画面上画圈 → A 屏幕浮现笔迹、位置正确
5. B 点挂断 → 两端回待命 → 可再次呼叫
6. 角色互换重复 2-5

**T3 边界**
- B 开飞行模式 15s：A 自动结束（AC-5）
- A 锁屏 30s：自动结束（AC-6）
- 通话中来电：语音让路、屏幕共享继续、结束后恢复
- 旋转：横屏游戏画面在 B 端正确 letterbox
- 杀进程重开：配对仍在、待命恢复（AC-7）

## 9. 里程碑 ↔ 需求映射

| 里程碑 | 覆盖 | 验收 |
|---|---|---|
| M0 链路 | FR-1,2,3,4,7,8,9 | AC-1,2,4,5,6,7 |
| M1 标注 | FR-5,6 | AC-3 |
| M2 体验 | FR-10~14 | 双端弱网/后台场景回归 |
| M3 加分 | FR-15~18 | — |
