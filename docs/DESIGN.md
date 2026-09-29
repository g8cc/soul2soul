# Soul2Soul 技术方案

> 异地恋人实时陪伴 App：一方共享屏幕，另一方实时观看，并用画笔标注 + 语音通话"陪玩"。
> 已确认的边界：只标注/语音，不做远程控制；不上架商店，仅两人侧载使用；双方均在国内；
> 自建 WebRTC（自有云服务器）；原生 Kotlin；目标机型小米 + 华为（新款）。

---

## 一、总体架构

```
 ┌────────────────┐                                    ┌────────────────┐
 │  她的手机(观看)  │                                    │ 你的手机(共享)   │
 │                │                                    │                │
 │ SessionActivity│      ① 信令: WebSocket(自建服务器)    │ScreenShareService
 │  观看远端屏幕    │ ◄──────────────────────────────►   │ MediaProjection采集
 │  画笔→DataChannel│                                   │ 硬编码 H.264     │
 │  麦克风(语音)    │      ② 媒体: WebRTC (P2P 优先)      │ 麦克风(语音)      │
 └───────┬────────┘                                    └───────┬────────┘
         │        屏幕视频流 / 双向语音 / 标注DataChannel          │
         └─────────────────── P2P 直连 ──────────────────────────┘
                        │ 直连失败时
                        ▼
              ┌─────────────────────┐
              │ 你的云服务器           │
              │  - 信令 ws (8080)     │  Node.js, 负责: 配对/呼叫/SDP/ICE 中转
              │  - coturn (3479+媒体口)│  STUN/TURN 中继 (3478 被 derp 占用)
              │  - data/pairings.json │  配对关系持久化
              └─────────────────────┘
```

两台手机之间的链路分三层，全部走 WebRTC：
1. **屏幕视频**：共享端 MediaProjection 采集 → libwebrtc 硬编码 → 观看端渲染；
2. **语音**：双方麦克风 → WebRTC 音频轨道（带回声消除），双向通话；
3. **标注**：WebRTC DataChannel（低延迟、可靠、有序），观看端画 → 共享端悬浮窗显示。

信令只走自建 WebSocket 服务器：配对、呼叫/接听、SDP/ICE 交换、挂断。

## 二、技术选型

| 项 | 选择 | 理由 |
|---|---|---|
| 客户端 | 原生 Kotlin, minSdk 29 | MediaProjection/悬浮窗/WebRTC 原生 API 最稳；两台目标机都是近年的安卓机 |
| UI | 经典 View 体系 + Material3 | 悬浮窗层必须是 View；全 View 保持一致性，工程最简单 |
| WebRTC | `io.getstream:stream-webrtc-android`（预编译 libwebrtc AAR） | 避免自编译 libwebrtc（极其痛苦）；API 即官方 `org.webrtc.*` |
| 信令 | Node.js + ws，JSON 协议 | 几百行搞定，服务器上 `npm i && node` 即跑；后续想换语言也容易 |
| NAT 穿透 | coturn（Docker），STUN+TURN，静态密钥 HMAC 时限凭证 | 国内移动网络 P2P 成功率低，TURN 是必须的兜底 |
| 屏幕编码 | libwebrtc 默认（硬编 H.264，720p@30 起，自动码率自适应） | 屏幕内容静态占比高，码率约 0.5~2.5 Mbps |

## 三、会话流程（呼叫模型）

1. **配对**（一次性）：A 在 App 里申请 6 位配对码 → 微信发给 B → B 输入 → 服务器把两个 deviceId 绑死并持久化。此后只允许这对设备互相呼叫。
2. **呼叫**：A 点"一起玩" → 服务器转发 `invite` → B 弹高优先级通知（点击进入接听界面）。
3. **接听**：B 点接受 → 双方拿到服务器下发的 ICE 服务器列表（含 TURN 时限凭证）。
4. **媒体建立**：A 收到 `accepted` 后弹系统屏幕录制授权 → 启动前台服务（先 `startForeground`，再 `getMediaProjection`，Android 14+ 顺序强制）→ 创建 PeerConnection、DataChannel、视频/音频轨道 → 发 offer → B 应答。
5. **陪玩中**：A 切到消消乐正常玩；B 全屏看 A 的屏幕，画笔/语音实时互动；A 屏幕上以"不可触摸的全屏标注层 + 小控制气泡"呈现 B 的笔迹和结束按钮。
6. **结束**：任一端点"结束"（A 的悬浮气泡 / B 的挂断按钮 / 通知栏操作）→ `bye` → 双方释放，A 的悬浮窗与通知消失。断网/进程被杀也会触发单侧自动结束。

## 四、信令协议（JSON over WebSocket）

所有消息 `{ "type": "...", ... }`。服务器按配对关系把消息转发给对方。

| 方向 | type | 说明 |
|---|---|---|
| C→S | `hello` {deviceId} | 连接后报到，服务器回 `registered`{paired, peerOnline} |
| C→S | `pair.request` | 申请配对码 → S 回 `pair.code`{code}（10 分钟有效） |
| C→S | `pair.enter` {code} | 输入对方码 → 双方收到 `paired`{peerOnline} |
| C→S | `invite` | 发起呼叫 → 对方收到 `incoming`{iceServers}；不在线则回 `peer.offline` |
| C→S | `accept` / `decline` | 接听方回应 → 对方收到 `accepted`{iceServers} / `declined` |
| 双向 | `sdp` {sdp:{type,sdp}} | 原样转发 |
| 双向 | `ice` {candidate:{...}} | 原样转发 |
| 双向 | `bye` | 挂断 → 对方收到后清理；对端掉线服务器代发 `peer.gone` |

`iceServers` 由服务器在 `incoming`/`accepted` 里下发：`[{urls:[...], username, credential}]`，
TURN 凭证为 HMAC-SHA1(静态密钥) 时限凭证（过期时间 = now+1h），泄露也只在一小时内有效。

## 五、关键设计

### 5.1 屏幕采集链路（共享端）
- `MediaProjectionManager.createScreenCaptureIntent()` 拿授权 → **必须先启动 `foregroundServiceType="mediaProjection|microphone"` 的前台服务并 `startForeground()`**，再创建 VirtualDisplay（Android 14+ 硬性顺序）。
- 采集分辨率：长边压到 1280、偶数对齐、30fps；libwebrtc `ScreenCapturerAndroid` 自动处理屏幕旋转/尺寸变化。
- 系统会在通知栏显示"正在录制屏幕"，这是系统行为，无法隐藏。
- Netflix/银行类 App（FLAG_SECURE）会黑屏，系统保护，不处理。
- 锁屏后 VirtualDisplay 停止出帧：v0 做成"无帧 30 秒自动结束会话"（防止挂死）。

### 5.2 标注链路与"回声"处理
- 观看端：笔迹画在渲染视图上方的透明 View，**本地立即渲染**（零延迟手感），坐标归一化到视频帧矩形（考虑 letterbox）后经 DataChannel 发出。
- 共享端：`SYSTEM_ALERT_WINDOW` 悬浮窗（不可触摸全屏层）按归一化坐标映射回本机像素渲染。
- **回声**：共享端屏幕被采集时会把标注悬浮窗一起采进去，观看端会看到自己笔迹的"延迟副本"。策略：默认**激光笔模式**（两端 2 秒淡出），回声与本地笔迹短暂重叠几乎不可察觉。持久文字标注（v1.1）改为观看端只显示回声、不本地持久渲染，避免重影。
- 悬浮窗同时承载控制气泡（可拖动）：结束按钮 + 静音开关（v1.1）。

### 5.3 坐标映射
- 双方分辨率/长宽比不同：观看端把触点归一化为 `(0..1)` 相对"视频画面矩形"（`SCALE_ASPECT_FIT` 在视图内的 letterbox 矩形，由首帧宽高计算）；共享端 `xN * 屏幕宽, yN * 屏幕高` 直接还原。视频帧与共享端屏幕是同一内容，归一化天然对齐。

### 5.4 语音
- 双方各加一条麦克风音频轨道（libwebrtc 自带 AEC/NS/AGC），通话模式下走扬声器。
- 共享端在游戏内时听声音靠扬声器外放；耳机场景 v1.1 优化选路。
- 游戏声音共享（`AudioPlaybackCapture`，Android 10+）列为 v2：需要自建 AudioTrack 桥，成本可控但先不做。

### 5.5 在线与推送（国内现实）
- v0 不接厂商推送：两端常驻一个低优先级前台服务（"在线待命"）持有信令长连接；首次运行引导用户开 **自启动 + 电池无限制 + 后台弹出界面（MIUI）**。
- 已知妥协：进程被杀时收不到呼叫。v2 可接 MiPush/HMS 厂商通道做"呼叫唤醒"，或服务器在重连后补发 `missed`。
- 服务器心跳 30s，客户端断线指数退避重连。

### 5.6 生命周期矩阵
| 事件 | 共享端 | 观看端 |
|---|---|---|
| 来电话 | 屏幕共享继续，语音通道路由给通话 | 同左；WebRTC 音频自动暂停 |
| 锁屏 | 停止出帧 → 30s 无帧自动结束 | 保持观看，画面停格 |
| 切后台 | 继续共享（前台服务活着） | 继续观看（可开小窗/画中画，v1.1） |
| Wi-Fi↔4G | ICE 自动切换/重连，失败 10s 结束 | 同左 |
| 进程被杀 | 对端收 `peer.gone` 自动结束 | 同左 |

## 六、安全与隐私
- 媒体走 DTLS-SRTP 加密；信令 v0 用明文 ws + deviceId（不上架、家庭场景可接受），**v1 升级 wss + 配对时约定的 token**（服务器加 TLS 即可，协议不变）。
- TURN 凭证时限化（HMAC 静态密钥）；服务器只转发给"已配对"的两台设备。
- 不录屏不落盘，无任何历史存储；会话数据只存在于内存。
- 隐私习惯：共享时短信验证码、私聊内容对对方可见——产品上把"结束"做到一步可达（悬浮气泡 + 通知栏 + 观看端）。

## 七、目标机型与权限

| 权限 | 用途 | 时机 |
|---|---|---|
| 屏幕录制授权（系统弹窗） | MediaProjection | 每次会话 |
| 悬浮窗 SYSTEM_ALERT_WINDOW | 共享端标注层/气泡 | 首次使用"开始共享"前引导 |
| 通知 POST_NOTIFICATIONS | 呼叫/待命通知 | 首次启动（Android 13+） |
| 麦克风 RECORD_AUDIO | 语音 | 首次会话 |
| 前台服务 mediaProjection / microphone / dataSync | 采集/语音/待命 | 清单声明 + startForeground |

**⚠️ 华为 HarmonyOS NEXT 检查**：如果她的华为手机升到了"纯血鸿蒙"（HarmonyOS NEXT，不能运行安卓 APK），本 App 无法安装。升级前务必确认：设置→关于手机→"HarmonyOS 版本"，**安卓版鸿蒙（2.0~4.x）可以装 APK，NEXT 不行**。

**MIUI**：自启动、省电策略=无限制、后台弹出界面（收呼叫通知必需）。
**华为 EMUI/HarmonyOS(安卓版)**：应用启动管理→手动管理（自启动/关联启动/后台活动全开）。
首次运行弹一次"权限引导页"，图文带跳转（v0 先做纯文字提示 + 系统设置跳转）。

## 八、服务器部署（你的云服务器）

```
server/
  src/index.js          # 信令服务器 (ws://0.0.0.0:8080)
  turn/docker-compose.yml  # coturn: 3478/udp+tcp + 49160~49200/udp
```
- 需开放：8080/tcp（信令）、3478/udp+tcp、49160-49200/udp（TURN 媒体端口段）。安全组记得放行。
- `TURN_STATIC_AUTH_SECRET` 写在 `.env`（docker-compose 和信令进程保持一致）。
- 带宽预估：仅 TURN 中继时约 1~2.5 Mbps。每天陪玩 1 小时、全程中继 ≈ 0.5~1 GB/天；同一 WiFi 下 P2P 直连则服务器零流量。国内云流量单价下，正常使用每月几块钱。
- 外网地址在 `app/build.gradle.kts` 的 `SIGNALING_URL` 一行配置（v0 用 ws://服务器IP:8080/ws）。

## 九、里程碑

- **M0 链路打通**：两台手机装上 → 配对成功 → 呼叫/接听 → 能看到对方屏幕、能说话。（验收：消消乐画面在对方手机上流畅不卡顿，延迟目测 < 0.5s）
- **M1 标注**：观看端画笔 → 共享端悬浮窗显示；激光笔淡出；颜色/粗细。
- **M2 体验加固**：断线重连、锁屏超时、小窗观看（PiP）、权限引导页、missed call 补发、wss。
- **M3 加分项**：持久文字标注、截图留念（带标注"拍立得"）、游戏声音共享、厂商推送、小气泡拖动/静音。

## 十、已知限制 / 风险
1. 屏幕录制系统授权每次会话都要弹（Android 14+ 无法永久豁免），产品话术上做成"召唤仪式感"即可。
2. 国产 ROM 杀后台不可根除，v0 依赖用户配合白名单；极端情况收不到呼叫。
3. 游戏横竖屏切换时编码尺寸变化可能有短暂花屏（libwebrtc 会自适应），M2 观察。
4. TURN 中继下的延迟与流量高于 P2P；同城宽带 ↔ 宽带通常能直连。
5. 视频首帧前观看端无画面，用"连接中"动画占位。
