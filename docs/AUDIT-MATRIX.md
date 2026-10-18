# AUDIT-MATRIX.md — 审计状态机矩阵（规范见 AUDIT.md L1）

判定标记：`PASS`=已核有gate/用例 · `GAP`=确认有问题（须修复+登记gate） · `⬜`=待填空格（审计欠债，t0 计数） · `N-A`=该组合不可能发生

## 1. 共享端涂鸦/悬浮窗（AnnotationOverlayService）

状态：`球位空闲` / `自画开启`（层+工具条在、球已拆）

| 事件＼状态 | 球位空闲 | 自画开启 |
|---|---|---|
| 点球（轻点/长按） | PASS 进自画（拆球+挂层）；抬手双触发已锁 v0.2.37 | N-A 球已拆收不到 |
| 拖球 | PASS 钳制在屏内（DoodleBallPolicyTest ×4） | N-A |
| 摇一摇 | PASS 进自画 | PASS 退出+还球（toggle→finishDoodle） |
| 点「完成」 | N-A 无工具条 | PASS v0.2.44 修复：还球收口 finishDoodle，OverlayBallWiringTest 锁定 |
| 自画层 addView 失败 | N-A | PASS c107105 m5：失败分支还球 |
| 旋转/配置变化 | PASS 球回默认位 | PASS 退自画+还球（笔迹随窗清） |
| 服务销毁（结束共享） | PASS 全窗拆除 | PASS exitDoodle+hideBall |
| 悬浮窗权限会话中途被撤 | ⬜ 已挂窗是否被系统回收、下笔表现（L3-3，待真机） | ⬜ 同左（待真机） |
| START_STICKY 重启（intent=null） | ⬜ 系统重建后 onStartCommand 还球，但共享会话已亡——球是否孤儿常驻（待核） | N-A |

## 2. 共享会话（ScreenShareService / SessionActivity / PresenceService）

状态：`未开始` / `握手中(offer/ice待排)` / `共享中(live)` / `锁屏暂停(会话存续，v0.2.56)` / `收尾(stopping)`

| 事件＼状态 | 未开始 | 握手中 | 共享中(live) | 锁屏暂停 | 收尾(stopping) |
|---|---|---|---|---|---|
| bye / peer.gone | N-A 无会话 | PASS 未接听=上一轮呼叫回声必须忽略（SignalRouter + SignalRouterTest） | PASS PeerEnded 横幅+退出（同上） | PASS stopSession(sendBye) 后迟到=无害 | N-A |
| 断网（peer_lost） | N-A | ⬜ 连接超时路径真机待核 | PASS peer_lost 横幅（SignalRouterTest） | ⬜ v0.2.56 起锁屏期间会话仍活着，断网照常要报 peer_lost（旧版此时已自杀，反而掩盖）——待真机 | N-A |
| 息屏/锁屏（共享端） | N-A | ⬜ live 前锁屏不发 screenoff（gated `if live`），观看端对着冻结画面——待真机 | PASS 立即 screenoff+收回授权横幅 | PASS v0.2.56：**不再自动结束会话**（`screenOffStop`/`SCREEN_OFF_TIMEOUT_MS` 已删，画面冻在最后一帧·语音继续）；LockScreenKeepAliveTest L1/L2 + SessionEndDiagTest D3 反向锁。旧行为已抓到自因实锤（10-17 生产日志，米10 作被控端，0.2.55）：`18:02:00 screen_off detail=timeoutMs_15000 ice=CONNECTED` → 15.0s 后 `reason=screen_off_timeout ice=CONNECTED liveMs=1134232`，一通 18.9 分钟、拆线时媒体还好的会话是被自己的看门狗拆的；`17:45:41→17:45:43 detail=rescued`（1.6s 就解锁）为反证。⬜ 0.2.56 的保活语义**一次都没采到**（米10 20:50 才升 code=56、此后无通话，米15 仍 55），实锤只证旧语义有害、不证新语义可用；HyperOS 是否自己拆掉 MediaProjection（`projection_stopped`）只能真机证 | N-A |
| 解锁（共享端） | N-A | — | PASS v0.2.45：screenon anno+ctl 双通道补发，横幅不再被弱网丢包卡死（AuditRound2WiringTest） | PASS screenon 撤横幅 + 复位 `remoteScreenPaused`（同一条路径，LockScreenKeepAliveTest L4）；旧"撤停格计时"随看门狗一起删除 | N-A |
| 对方取消呼叫 | PASS 来电通知同步撤销 ID_CALL（v0.2.45，AuditRound2WiringTest） | PASS CallerCanceledBeforeAccept：停铃+提示+finish | N-A 已接听走 CanceledAfterAccept（授权阶段主叫反悔，别让对方数 30 秒） | — | N-A |
| 操控授权中途被收回 | N-A | — | PASS v0.2.45：ctl_denied→applyControlMode(false) 退出死模式+横幅说明；controlMode 写入全文件收敛一处（AuditRound2WiringTest） | PASS 锁屏收回专用 DeniedScreenOff（CtlConsentMachineTest） | N-A |
| 通知栏动作按钮可见性 | N-A | — | PASS v0.2.46：静音/操控/结束三键全带图标——icon=0 纯文字按钮在折叠态不渲染，MIUI/HyperOS 常驻通知默认折叠（真机实锤"按钮时有时无"；t0 addAction(0 守卫） | N-A | N-A |
| 快速重拨/服务复用 | ⬜ 双 start 是否丢新 projection（SSS 复位段）待真机复现 | — | — | — | PASS 迟到通知动作 webRtc==null→stopSelf（SSS:90）；stopping 幂等 |
| 信令排队/回放 | PASS pendingSignals 入队→open 后回放并清（SSS:175/196） | — | — | — | PASS 三处清空：start 复位/回放后/onDestroy（SSS:122/197/223） |
| 进程被杀 START_STICKY | ⬜ intent=null 重建路径（同 §1 孤儿球，一并真机核） | — | — | — | — |
| 息屏待命保活（Presence 长连接） | PASS v0.2.51：配对期持 PARTIAL_WAKE_LOCK（acquire 带 6h 超时+5h 自续期，onCreate 排在 startForeground 后，onDestroy/unpaired 成对释放，AuditRound2WiringTest 锁三对偶）；FGS 类型 dataSync→specialUse（Android14 dataSync 有每日时长上限会定时停服）；服务器30s ping/OkHttp20s ping 不变 | N-A 会话期保活归上面"息屏/锁屏"行 | N-A | N-A | N-A |
| 息屏待命真机复验 | ⬜ 双真机配对→静置锁屏≥5min→唤醒互呼直达、logcat 无 terminate/重连风暴（wakelock+豁免的实际效果只能真机证） | — | — | — | — |
| 终止原因可追溯（diag.*，v0.2.55） | PASS 未真正起流（connectStartedAtMs≤0）不发帧，不产噪声 | PASS reason=connect_TIMEOUT/sdp_* + fg 位（Android14+ 后台起采集被拒时可辨） | PASS 所有本地收尾汇流 close()→diag.session（reason/ice/setupMs/liveMs/fg/screenOn）；SessionEndDiagTest D1/D2/D4 锁 | PASS 锁屏事件先走 DataChannel（服务器看不见），另发 diag.event screen_off（detail=keepalive）；v0.2.56 起不再有 screen_off_timeout；D3 反向锁 | PASS local_stop/user_hangup/remote_bye 三分叉（first-wins，上游真因不被覆盖）；10-18 实锤的 `liveMs/setupMs` 失真已在 v0.2.58 repo 侧修好（`SessionTiming` 拆开"是否连着"与"何时连上"，t1 单测 + D7/D8 源码锁），**10-18 11:43 米15↔米10 真机采到第一条**：`reason=remote_bye ice=DISCONNECTED setupMs=748 liveMs=14195`——ICE 已 DISCONNECTED 而 liveMs 仍是正的 14.2s（同一文件里 0.2.55 的旧读数是 `setupMs=1135965 liveMs=0` 垃圾值，形成对照）。⬜ 但同一行的 `path`/`proj` 两列**客户端从未 put**（`SelfCheck.reportSession` 里根本没有这两个键，服务器打印出来恒为 `_`；`WebRtcClient` 已算出 `selectedPath` 却只进了 logcat :931/:952），P2P 与 TURN 转发至今不可辨，而米15 无 adb=logcat 这条路对它不存在；读旧版本（≤0.2.57）日志时 liveMs 判据仍不可信：见 OPEN-QUESTIONS §A.7 ③ |
| 启动失败不谎报（v0.2.56 盲点） | N-A | ⬜ `fgs_start_failed` / `webrtc_start_failed` 两条新事件首次真机命中待核 | PASS 两处 catch 各带一条 diag.event，且起流失败先 noteEnd 抢在 local_stop 前（PiPLockScreenGuardTest L3/L4） | N-A | N-A |
| 观看端本端锁屏→解锁（黑屏排查，v0.2.55→v0.2.57） | N-A | ⬜ 解锁后画面是否真的回来，仍待真机双端肉眼＋日志互证 | PASS 判据纠偏（10-17 17:43~18:02 双真机日志实锤）：`surface_destroyed` 之后**每次都有** `surface_created`+`surface_size`，"surface 没重挂"这条假设被否掉；同一批日志里每条 `viewer_resumed` 后 0.2s 内必跟一条 `video_stall`，是观看端**自己**锁屏造成的假告警（解码没有渲染面必然停帧），将 G 的真信号埋掉了。v0.2.57 把 `video_stall` 门控补到本端 surface 生命周期上，并新增正面判据 `video_resumed`（surface 重建→首帧毫秒数）。⬜ `video_resumed` 从未真机跑过：没有它就无法区分"流停了"与"帧回来了但没画出来" | ⬜ 残留：`remoteScreenPaused` 只有 `screenon` 一条恢复路，若解锁瞬间 anno+ctl 两条通道同时丢（data channel 正断），横幅留着、本端 `video_stall` 也被永久门掉。候选对策=拿"首帧证据"（`inboundVideoFps>0` ⇒ 对方画面在动 ⇒ 已解锁）自动复位；但静止画面本来就不出帧，所以这条只在"有帧=没锁"方向单向使用。没有真机日志证明它真丢过，先登记不改（同 B3 原则） | N-A |
| 观看端进/出小窗（PiP，v0.2.56） | N-A | — | PASS 锁屏/息屏引起的 onUserLeaveHint 不再进小窗（`isInteractive` 前置判断）；进出各报 `pip_enter`/`pip_exit`（PiPLockScreenGuardTest L1/L2）。~~surface 只观测不重挂，若日志证实 destroyed 后无 created 则补重挂~~ 该触发条件已被 10-17 双真机日志否证（created 每次都回来），不再按"重挂 surface"方向改渲染链；门控按 surface 而非 `onPause`，小窗期间的真卡死不会被放过（LockScreenKeepAliveTest L5） | N-A | N-A |
| 信令断 vs 媒体断（v0.2.56） | N-A | — | PASS(校正) 会话期 onState 断/续各报一条，但 **`signaling_down` 到不了服务器**（三类 diag 都要求 `isConnected`，而它按定义就在断线时触发）→ 本机 logcat 有、服务器只能看 `signaling_up` 的 `downMs=`（v0.2.57 把断口长度收口进 `reportSignaling`，PiPLockScreenGuardTest L4c 锁；L4b 只锁"报了"，锁不到"采得到"）**——0.2.57 及之前成立，v0.2.58 起 `signaling_down` 改走 `DiagBacklog` 排队（见下一行），复活后能补到服务器，`downMs=` 仍是主判据但不再是唯一读数** | 同左（锁屏期间信令掉也照报；锁屏→解锁这段时间的 `downMs` 就是 Doze 嫌疑的量化读数） | N-A |
| 断线期埋点排队与回放（`DiagBacklog`，v0.2.58） | PASS 未连接时 `sendDiag` 不丢帧、落 `filesDir/s2s-diag-backlog.ndjson`（SessionEndDiagTest D9 锁"必须 enqueue"+"绝不 `.connect(`"——自建连接会绕过 hello 鉴权并把 i/o error 洗白成"没上报"） | ⬜ 握手中掉线的帧是否真能在回放后与服务端 `detail=at_` 对上，待真机 | ⬜ 已写完排队+重连后 `flush`（PresenceService `onState(connected)` 里、hello 之后），**尚无一条真机采样**：米15 无 adb，这条正是为它建的，必须等它在生产日志里出现带 `detail=at_` 的行才算验过。10-18 11:43 那通会话信令全程未断→`detail=at_` 命中 0 条属预期；但同日 11:40/11:42 米15 有两次已确认的 standby 掉线，日志里同样 0 条——因为那两次没有会话期事件可排（`screen_off/on` 只在 `ScreenShareService` 里发），所以这条通路**仍是零采样**，不是"验证过不触发" | ⬜ 锁屏→Doze 冻结期是队列最容易满的场景（上限 200 条、超 96KB 整体重写截断），丢弃行为待真机 | ⬜ 收尾帧（`session_end`）在 socket 已死时排队=本版本核心收益，但"崩溃时最后一帧还在文件里"只测过写语义、没测过进程真死 |
| 信令假死被服务器主动拆（`[ws] pong 超时 terminate`，30s 内没回 pong 即 terminate；10-17 一晚 6 次） | ⬜ 待机期被误杀=对端看到"离线"，可接受但会放大重连风暴 | ⬜ 误杀即 peer.gone，与"对方没接"难分 | ⬜ **未解风险**：双端都把 `peer.gone` 当挂断直接拆（`ScreenShareService` bye/peer.gone 分支、`SignalRouter.PeerEnded`），而媒体是 P2P 的、与信令 socket 死活无关。v0.2.57 已把取证补上：**拆除之前**两端各报一条 `peer_signal`（detail `sig=gone|bye`，ice 取当时实况），`ice=CONNECTED` 即"画面语音都还在、会话被一句信令离线拆掉"的直接证据（PeerSignalProbeTest P1/P2 锁顺序，P3 锁文档登记）；行为**照旧不改**，等这条差分回来再定对策（放宽 pong 窗口=服务器高风险区需批准 / 收到 peer.gone 先探 ICE 存活再拆=可纯客户端做）。**10-17 补证：还有一种比 pong 更快的死法不需要 30s**——米15 待机中 `registered` 后 `aliveFor=2.2/8.2/8.2/9.3/12.2/12.6s` 就 `close code=1006`（22:03–22:28 六次，重连约 7–9 分钟一轮，像 Doze 维护窗口放出的短连接），每次都广播 `peer.gone`；会话期间命中同一路径即被拆，故"先探 ICE 再拆"那条客户端对策的优先级高于 pong 调参。⚠️ 别拿服务端 `sessionMs` 当"被踢时还在通话"：`ws.sessionAt` 会话结束不重置，18:02 结束、18:10 被踢的 socket 会显示 `sessionMs=1614240`（PROTOCOL §2.3 已加读法警示） | ⬜ 锁屏后 Doze 冻结客户端最易命中此项，与"锁屏保活"新语义直接冲突（0.2.56 删了自杀看门狗，但信令假死仍会拆），复现必查 | N-A |
| 观看端入口球（小铅笔）拖动（v0.2.55） | N-A | N-A 球未显 | PASS 可拖到画面任意角落：slop 内仍算点击（performClick 保住菜单展开+无障碍），拖出由 clampBallIntoParent 钳回；父容器重布局（转屏/系统栏）时重新钳制；ViewerBallDragTest B1–B3 锁 | — | N-A |

## 3. 配对三通道（找回/互摇/扫码）

状态：`未配对无历史` / `未配对有历史` / `召回挂起(≤60s)` / `已配对`

| 事件＼状态 | 未配对无历史 | 未配对有历史 | 召回挂起 | 已配对 |
|---|---|---|---|---|
| 发起找回 | PASS 服务端协议 t3 黑盒已锁（14 项） | 同左 | ⬜ 双请求覆盖（pendingRecallFrom 单槽）待真机 | N-A 直接进会话 |
| 被找回 accept/decline/超时 | N-A | PASS offerRecall 落槽+onResume 领取只领一次（SharedFlow 无 replay 的补偿）；65s 通知自动撤销（v0.2.45） | PASS claimRecall synchronized + 60s 过期（与服务端 pendingTtl 对齐）；⬜ 本机时钟回拨/快拨影响 wall-clock 判据，风险低待真机 | N-A |
| 互摇 | ⬜ t3 协议 PASS，客户端手势入口双端真机未闭环（#43） | 同左 | N-A | N-A |
| 扫码 | ⬜ 同 #43 | 同左 | N-A | N-A |
| 解绑 unpaired | N-A | PASS 清 paired/token/InboxStore（PresenceService handleSignal） | — | 同左 |
| 进程重启/断线 | ⬜ 自启动+电池豁免在真机 ROM 下存活率（同 §4） | — | — | PASS 重连指数退避 3→30s 上限（onState 路径） |
| 配对成功即换 token 重连 | N-A | N-A | N-A | ⬜ **实测有抖动**：双模拟器 18:34:10.244 `paired` → .262/.492 服务器对两端各广播一次 `peer.gone`（旧 socket 关闭，2ms 后新 socket registered）→ 对端状态胶囊会闪一下"对方离线"。服务器加宽限期（peer.gone 延后 2~3s 且期间同 deviceId 重连则不发）属高风险区，需用户批准；先在真机确认肉眼可见与否 |

## 4. 权限引导（通知/麦克风/悬浮窗/无障碍）

| 权限＼场景 | 首次授予 | 永久拒绝 | 系统重置为"每次询问" | 会话中途撤销 |
|---|---|---|---|---|
| 通知 | PASS 引导存在 | PASS areNotificationsEnabled 预判 + SecurityException 兜底（showIncoming/Recall 不崩） | ⬜ 待真机 | ⬜ 常驻会话通知被撤是否影响 FGS（Android12+ 语义）待真机 |
| 麦克风 | PASS 时机前置到切语音（v0.2.33 实锤修复） | PASS #31 解释弹窗+设置直达 | PASS #31 重置路径引导 | ⬜ 采集中被撤：仅下次打开感知（AudioRecord 报错面未真机核） |
| 悬浮窗 | PASS 引导+类型回退（v0.2.22 Android7 TYPE 锁定） | PASS addView 失败分支还球（c107105 m5） | 同左 | ⬜ 已挂窗被系统回收、下笔表现（同 §1，待真机） |
| 无障碍 | PASS 引导 MIUI 路径+反馈不依赖 toast（#13） | PASS ctl_need_acc toast + 授权对话框乐观写回滚（CtlConsentMachine.setTarget） | ⬜ accConnected 区分真撤销/被冻已实现（#33 代码侧闭环），真机复验仍挂 | PASS ctl_error(reason) 横幅分级+4s 节流回灌（routeData ControlFailure + CtlConsentMachineTest） |
| 电池豁免 | PASS 引导对话框存在 | — | PASS v0.2.51：askedBattery 单向闩改为 batteryPromptAt 时间戳节流（3 天内不再弹、超期未豁免再引导）；AuditRound2WiringTest 锁"不得回退成只问一次" | N-A |

## 5. 留言（面板/文字/语音/撤回）

状态：`面板关` / `面板开` / `录音中` / `撤回窗口(≤5min)`

| 事件＼状态 | 面板关 | 面板开 | 录音中 | 撤回窗口内/外 |
|---|---|---|---|---|
| 按返回键 | PASS 正常退出 | PASS v0.2.45：先收面板不退页（OnBackPressedCallback + AuditRound2WiringTest） | ⬜ 录音中返回=取消路径待真机 | N-A |
| 点输入框 | PASS 聚焦弹键盘（常规） | PASS v0.2.47：微信同款——面板先收、键盘上位（AuditRound2WiringTest） | N-A | N-A |
| 表情插入/键盘互斥 | PASS | PASS 面板替代键盘位、语音模式互斥（v0.2.33 崩溃锁定：t0 INPUT_SERVICE 检查） | N-A | N-A |
| 文字发送×断网 | PASS v0.2.45：send() 返回 false 保留草稿+提示，与语音路径同判据（AuditRound2WiringTest） | 同左 | N-A | N-A |
| 发送后表情墙收起 | — | PASS v0.2.50：send() 成功即 setEmojiPanel(false)（微信同款）；失败路径先 return，草稿+面板都留着（AuditRound2WiringTest 锁顺序） | N-A | N-A |
| 语音发送×失败 | — | — | PASS too_short/上传失败/上送失败三分支齐（uploadAndSend） | N-A |
| 回听×页面重建 | PASS loadingId 失配即弃回调 | — | — | ⬜ mine 语音本地缓存丢失后点击（低危：只提示不崩） |
| 撤回 | PASS 5min 窗口+墓碑提示行+双端 msg.recalled（#40；协议 t2/t3 锁） | N-A | N-A | 窗口外服务端拒=PASS（t2） |
| Android7 emoji 覆盖 | PASS 字体 cmap 实测裁剪清单（v0.2.43） | 同左 | N-A | N-A |
| 表情面板分组 | PASS 面板关时 tab 状态保留（emojiTab 单一内存态） | PASS v0.2.48：四组 tab（笑脸/手势/爱心/符号），全表 Android7 cmap 验证+FE0F 强制，EmojiPanelCatalogTest 锁定；回滚删组或塞 🥰 即变红。v0.2.49 模拟器全链验证：切组/连插/草稿保留 | N-A | N-A |
| 键盘开着×点😊 | PASS 常规 | PASS v0.2.49：setEmojiPanel 先 clearFocus 再 hideSoftInput（HIDE_NOT_ALWAYS），AuditRound2WiringTest 锁定顺序；模拟器实测面板开+键盘收+草稿在。⬜ MIUI 键盘回弹路径待真机复验（Mi5 注入权限被 MIUI 收回，需手动开「USB调试(安全设置)」） | N-A | N-A |

## 6. 主叫呼叫页（MainActivity outgoingOverlay 状态机）

状态：`NONE` / `RINGING（inviteSent 区分 准备中↔正在呼叫）` / `OFFLINE_PROMPT`；转屏已 configChanges 不重建。

| 事件＼状态 | NONE | RINGING | OFFLINE_PROMPT |
|---|---|---|---|
| 恢复对称性（每出口必清 Handler+复位） | N-A | PASS v0.2.52：accepted/declined/bye/call.canceled/unpaired/local.sessionEnded/local.sessionLive/CALL_TIMEOUT/projection_denied 九路全经 endOutgoing()（双 removeCallbacks + cancelOutgoing + inviteSent=false + NONE），AuditRound2WiringTest L6 逐 case 锁；媒体超时另设 mediaGen 守卫 | 算了/挂断→endOutgoing→NONE |
| overlay 可见性单点收口 | PASS | PASS L6：`outgoingOverlay.visibility` 全文件仅 updateUi 一处赋值（gate 计 ==1） | 同左（同函数分支） |
| 假振铃文案 | — | PASS L：peer.offline 保持 inviteSent=true，振铃期文案="正在呼叫 TA…"（非"准备呼叫中"）；v0.2.52 模拟器实测 t+1s 正确 | — |
| 离线转留言漏斗 | — | PASS 空 reason→ringback(6s,RINGBACK_MS)→OFFLINE_PROMPT；非空 reason(too_fast/not_authed)→toast 不进漏斗（L5） | PASS 去留言→MessageActivity（模拟器实测打开）、TA不在线文案+给出路 |
| 权限中途收回/授权拒绝 | projectionLauncher else→toast+endOutgoing | PASS stale 守卫（!outgoingPending 时投影结果作废不复呼） | N-A |
| 多入口多出口配对 | 一起玩起手 callGen+=1 复位 | 二次点一起玩=取消（send cancel/bye+endOutgoing）；挂断=onHangupClicked | 去留言/算了/挂断三路 |
| 对方接听后被控端跳界面（无感化） | 会话结束后无跳页需求 | PASS v0.2.54：去掉 accepted→startActivity(MainActivity) 强拉前台（PresenceService 仅保留 ScreenShareService.start 起流）；采集/注入/悬浮均不要求前台，被控端停在当前界面=操控方看到的真实画面。回前台防卡：MainActivity.onResume 用 ScreenShareService.isLive() 兜底 endOutgoing。SeamlessControlStartTest S1–S3 锁（加回跳页/删起流/删兜底 均变红） | N-A |
| 降级老机型（Android7 Mi5） | ⬜ 全屏 overlay 渲染待真机 | ⬜ 假振铃 6s 手感待真机（Mi5 无注入权限，手动验证） | ⬜ 待真机 |

## 7. 连接状态胶囊（MainActivity connChip / Presence.connState）

状态：`CONNECTING` / `OFFLINE` / `CONNECTED`(×未配对·对方在线·TA不在线)。合并"信令连接×对方在线"为单枚带底色胶囊。

| 事件＼状态 | CONNECTING(黄) | OFFLINE(红) | CONNECTED·未配对(灰) | CONNECTED·在线(绿) | CONNECTED·离线(蓝) |
|---|---|---|---|---|---|
| 恢复对称（断线/重连/回前台都刷） | PASS v0.2.53：Presence.onState 两分支落 connState+广播 local.connState（ConnStatusChipTest L1）；MainActivity 消费事件 updateUi（L2）；onResume 读 Presence.connState 兜底（Activity 错过 SharedFlow） | 同左（onState(false)→OFFLINE） | 已连接后按 paired 分档 | 同左 | 同左（peer.gone/online 经既有 updateUi） |
| 取色单点收口 | PASS L3：connChip/connDot 取色各一处且都在 updateUi 内（散写=某态漏刷，与呼叫横幅假象同类风险） | 同左 | 同左 | 同左 | 同左 |
| 五态话术齐 | PASS L4：conn_connecting/offline/connected_unpaired/online/offline 全引用，漏一态即红 | 同左 | 同左 | 同左 | 同左 |
| 权限/配对多入口 | 首启未连=黄→连上转档 | 拔网/服务端故障=红，退避重连回绿（不动退避逻辑） | 解绑 unpaired→paired=false 落灰档 | 配对 paired→绿 | peer 离线→蓝 |
| 降级老机型（Android7 Mi5） | ⬜ GradientDrawable.mutate().setColor 在 API24/25 实机渲染待真机截图 | ⬜ 同左 | ⬜ 同左 | ⬜ 同左 | ⬜ 同左 |

## 8. 待机与遥测覆盖（v0.2.59 StandbyProbe / diag.event role=standby）

状态：`未配对` / `配对·亮屏待机` / `配对·锁屏待机` / `配对·Doze` / `会话中`。
格子判据是**"这一格在服务器日志里真的出过数"**，不是"代码里埋了"。
10-18 起本机 dev server + 双模拟器（API34）已把**通路**那一半填掉：凡标 `PASS(模)` 的都是模拟器真出过数，
**它只证明"埋点会出数"，不证明"米15 上也是这样"**——所以米15/HyperOS 特有的判据（自然掉档、真 Doze、
被 ROM 杀、锁屏掉线的 `detail=at_`）一律保持 `⬜` 并写清缺哪一次采样，复现后用 `scripts/repro-capture.sh summary` 一次性对照。
0.2.59 已于 10-19 06:52:41 发到更新通道（`versionCode=59`，APK sha256 `a6aed2ff…`，
`curl -sI https://soul.lumi666.cloud/apk/soul2soul-latest.apk` → 200），`PASS(模)` 的列都注明了是怎么造出来的（强制手段不等于真机行为）。
**但通道上有包 ≠ 装了**：米15 最后一次上报仍是 `code=58`（10-18 22:10:09，`update=false`），
她打开 App 才会检查更新，所以本表里所有依赖米15 的格子在 `diag.version code=59` 出现之前**不许**改成 PASS。
**`PASS(真)` = Mi10 真机（HyperOS / Android 12 / API31，adb `7c6a9986`，线上生产 server）18:24 起实际出过数**，
比 `PASS(模)` 高一级但**仍不等于米15**：Mi10 在 deep-Doze 白名单里（`dumpsys deviceidle whitelist` → `user,com.soul2soul.app,10442`），
AOSP 的 Doze 网络限制对它根本不生效，所以凡涉及"真 Doze 行为"的格子，Mi10 出不了数也不能反证米15 没问题。

| 事件＼状态 | 未配对 | 配对·亮屏待机 | 配对·锁屏待机 | 配对·Doze | 会话中 |
|---|---|---|---|---|---|
| 信令 socket 死（`signaling_down`/`signaling_up`） | ⬜ 结构性不可见：`diag.*` 在未鉴权连接上被服务器丢弃（index.js 的 authed 门），**零采样是设计不是坏**（读日志口径见 AGENTS.md） | PASS(模)：10-18 14:42 `svc wifi/data disable` 25s 后恢复 ⇒ `signaling_down` 带 `at_` 到达、`signaling_up` 带 `downMs_3019 n_cell d_x`（新增两字段出数）；**PASS(真)**：18:35 Mi10 生产日志 `signaling_down at_1792319705147_reconnectAttempt_1_n_wifi` + `signaling_up reconnectAttempt_0_downMs_3248_n_cell`（单调 3.2s，走蜂窝复活），且**这一轮信令比 `net_down` 先死 52ms** ⇒ PROTOCOL 里"断网在前、信令死在后"被降级成"两种形态之一" | ⬜ 缺米15 锁屏掉线的 down→up 一对（**生产日志的 `detail=at_` 已不再是 0 命中**：10-18 18:35 起 Mi10 补回 11 条，缺的只是她那台设备的自然样本）；另注意 Mi10 那次的 down 沿 `screenOn=true`（MIUI 被通知点亮过屏幕），所以"灭屏状态下的断网 down→up"这一格严格说仍没采到 | ⬜ 缺 Doze 进入后的第一条 down（判"Doze 杀 socket"与"网络切换杀 socket"是否同一条路径）；Mi10 在 Doze 白名单内，这一格它出不了数 | ⬜ 会话期信令死亡是否也补报（`BacklogTiers` P0 配额下理论上必留，待真机日志确认） |
| 屏幕开关（`screen_off`/`screen_on`） | ⬜ 同「未配对」口径：receiver 在跑但报文出不了未鉴权连接 | PASS(模)：非会话期 `screen_off detail=live_0_k_3032`（receiver 不再 gated `if live` 这一改动已出数） | **PASS(真)**：Mi10 生产日志非会话期成对出数 `screen_on(18:24:29 detail=live_0_k_796015)`→`screen_off(18:25:08 k_796053)`→`screen_on(18:26:40 k_796145)`→`screen_off(18:26:46 k_796151)`，`k` 差与墙钟差自洽（98s vs 98s）⇒「待机期客户端一句话都不说」在真机上也已反证；⬜ 米15 侧仍缺（她侧 0.2.59 未装机） | ⬜ Doze 期屏幕本已灭，只缺"灭屏后再次边沿"是否被 ROM 吞掉（若无事件=冻结，非埋点坏）；Mi10 在 Doze 白名单内，这一格它出不了数 | PASS（既有）：会话期 screen_on/off 自 0.2.4x 起在生产日志有采样；StandbyProbe 用 `skipScreenEvent` 避免同一时刻两条真值（StandbyPolicyTest 锁） |
| 网络切换（`net_up`/`net_down`/`net_type`） | ⬜ 未配对期间无通路（同上） | **PASS(真)**：18:35 Mi10 生产日志首次出现 `net_type`（全天此前 0 条，含两台模拟器——模拟器只有一张网卡，结构出不来）：`net_down n_wifi k_796650` → `net_up n_cell k_796650` → `net_type from_wifi_to_cell`，18:37:38 恢复后又实时（不带 `at_`）收到 `net_up n_wifi k_796803` + `net_type from_cell_to_wifi` ⇒ **正反两个换网方向都出过数**，且方向一的三条走的是补报路径（换网瞬间乐观发送确实丢）；PASS(模) 那次（14:42 断唯一网卡）仍留在 PROTOCOL 作对照，因为它的顺序与这次相反 | ⬜ 缺米15 锁屏后是否报 net_*（与"待机断连"的差分判据） | ⬜ 缺 Doze 断网时 net_down 是否到得了——**到不了本身就是要的答案**（Doze 冻结 binder 回调）；Mi10 在 Doze 白名单内，这一格它出不了数 | ⬜ 会话期网络切换（`registerDefaultNetworkCallback` 与 ICE restart 是否互相掩盖，待日志；18:35 那次不在会话中，所以没碰到这条路径） |
| Doze 进出（`doze_on`/`doze_off`） | ⬜ 无通路 | ⬜ 亮屏待机通常进不了 Doze，此格预期长期为空（不许为凑数改成轮询上报） | PASS(模)：14:13 `cmd deviceidle enter` 逼出 `doze_on k_828/k_904`（两台、screenOn=false），14:16 `doze_off` 成对回来；**强制 ≠ 自然**，真机自发 Doze 仍 ⬜ | ⬜ 缺 HyperOS 自然 Doze 的时刻与 `signaling_down` 的先后 | ⬜ 会话中主动 `isDeviceIdleMode` 取样的对照（当前只在边沿报，会话期无读数） |
| App standby bucket 降级（`bucket_*`） | ⬜ 无通路 | PASS(模)：`am set-standby-bucket` 逼出整条链 `rare(prev=10)`→`rst(prev=40)`→`wset(prev=45)`→`act(prev=20)`，五档名字与 `prev=` 两两自洽（45 那格修对的直接证据）；**PASS(真)**：Mi10 待机首读就是 `bucket_x5 detail=prev_-1`（+心跳里 `b_5`），系统侧 `dumpsys usagestats` 对到 `standbyBucket=5 reason=d`，全机还有 `com.miui.voiceassist` 在 `50` ⇒ **HyperOS 确实有 AOSP 五档之外的私有档**。但 `prev=-1` 说明这条是**首读**而非边沿，所以它只证"待机状态下系统给的就是 5"，**不证**"待机过程中从 10 掉到了 5"——原假设"掉到 `bucket_rst`(45) 那一刻"在真机上还没被证实也没被证伪，期望值要放宽成"任何一次 `bucket_` 边沿（含 `x5`/`x50`）"；**10-19 补齐了对照的另一半**：复活后前台期心跳是 `b_10`、昨夜待机期是 `b_5` ⇒ 同一台机器上 MIUI 确实在 10↔5 之间换档，只是换档那一刻探针还没起来（首读 `prev=-1`），所以**边沿事件仍未取到**，取到的是"两个档都真实存在" | ⬜ 缺米15 锁屏后是否**自然**降档——注意期望值要改成"任何一次 `bucket_` 边沿（含 `x5`/`x50`）"而不是死盯 `bucket_rst`；模拟器静置 8 分钟 `b` 恒 10，顶不出这一格。**Mi10 侧 10-19 已给出反向读数**：18:29~21:04 连续 33 条心跳全程 `b_5`、期间零 `bucket_*` 边沿 ⇒ 锁屏静置 2.5h 里 Mi10 的档没再动过（它本来就在 5）；**但这不许外推到米15**——Mi10 整夜插着 USB 充电，充电本身就抑制 standby bucket 降级，米15 是电池过夜 | ⬜ 缺 Doze 与 bucket 降级的因果顺序（哪条先出现）；Mi10 在 Doze 白名单内，这一格它出不了数 | ⬜ 会话期 bucket 是否被顶回 active（若顶回，说明 FGS 改变了 bucket，属副作用需知） |
| 进程被杀（`aexit`，AEI 首读） | ⬜ 未配对时不存在"上一轮被杀"的可读语境（且无通路） | PASS(模)：`am force-stop`→重开→连上信令 ⇒ `r_10_i_100_a_0`；`adb install -r` ⇒ `r_16_i_125_a_0`（两条实测读数已写进 PROTOCOL 供校准）；**PASS(真)**：Mi10 真机（API31，AEI 可用）首读 `detail=r_10_i_125_a_179`——`r=10`+`a=179分钟` ⇒ 拿到的是**上一次**用户侧被杀，本次 `adb install -r` 的 `r=16` 那刻还没落库（模拟器同一动作报 `a_0`）。这条改写了读法：**`a=` 大于本次会话存活时间时不能当本轮死因**（详见 PROTOCOL 同条），也说明"每个进程只读一次"在复现期会错过刚发生的那次死；⬜ Mi5（API24/25）结构性不报（`reportPreviousExit` 有 `SDK_INT < 30` 早退，静态已核，不许当"已覆盖"） | **PASS(真)：本轮最硬的一格（10-19 07:03，Mi10 生产日志）**——待机 2.5h 心跳准点跑到 21:04:19（`k_805604`），21:07:09.990 socket `code=1006`（`aliveFor=9121611ms`＝不是 pong 假死、是被外部杀），此后**整整 10 小时零读数**；07:03 拉起后首次连上即报 `aexit detail=r_10_i_125_a_596`，而 ROM 侧 `dumpsys activity exit-info com.soul2soul.app` 的 #0 就是 `timestamp=2026-10-18 21:07:09.676 reason=10 (USER REQUESTED) importance=125`——**596 分钟正好回到 21:07，reason/importance/年龄三项到分钟对齐**，两侧独立来源互证 ⇒ 待机断连在这台 HyperOS 上的终点是**进程被 MIUI 杀**（`AutoPowerKill`），不是 socket 自己死、也不是 pong 超时。**这同时否证了原计划的枚举假设**：预期答案是 `REASON_SIGNALED`/`LOW_MEMORY`/`FREEZER`，真机给的是 `USER_REQUESTED(10)`，所以米15 那条要按"任何 `r=` 都算答案"去读、别只等这三个值。⬜ 仍缺米15 自己的 `r=`（她那台可能是完全不同的死因，且 22:10/22:37 两次 2.5~6.9s 的秒断是**另一种签名**，别拿 Mi10 的结论替她答） | ⬜ Doze 与 kill 的区分（此前完全不可知，本轮新增） | ⬜ 会话中不会发生（AEI 只在首次 connected 读一次，设计如此，不追轮询） |
| 保活锁（`wake_ok`/`wake_off`/`wake_fail` + 心跳里的 `w=`） | ⬜ 无通路 | **10-18 抓到的第二个"读数撒谎"**：`updateWakeLock()` 排在 `StandbyProbe.start()` 之前 ⇒ 首次 acquire 落在 `running=false` 上，`wake_ok` 全日志 0 条且心跳里 `w` 恒 0（锁其实拿着）。修顺序后 PASS(模)：15:00 两台各一条 `wake_ok`（一条直发、一条经补报带 `at_`）；心跳里的 `w=` 同步翻正——修复前四条心跳恒 `w_0` 而锁其实拿着、15:05 两台同时 `w_1`；⬜ `wake_off`、`wake_fail` 仍零采样（前者要走解绑、后者要 acquire 抛异常；`am stopservice` 试过硬停，FGS 直接回 `Error stopping service`） | ⬜ 缺锁屏期"锁还在不在"的正证（HyperOS 释放自研锁 = 睡死的首要候选，只能真机）→ **部分补上**：Mi10 真机 18:29:17 锁屏静置期心跳 `detail=k_796302_g_x_n_wifi_d_0_b_5_w_1_s_1`，`w_1` = 锁仍在 ⇒ 顺序修复在真机生效（模拟器时代同一格恒 `w_0`，是撒谎读数）；⬜ 仍缺"ROM 主动放掉这把锁"的那次边沿（`wake_off`/`wake_fail` 零采样） | ⬜ Doze 期锁与冻结的区分（Mi10 在 Doze 白名单内，这一格它出不了数） | ⬜ 会话期是否被 `ScreenShareService` 的锁覆盖（两把锁的语义要分开读） |
| 心跳缺口（`heartbeat` 的 `k=` 单调锚点，日志侧渲染成 `k_…`） | ⬜ 未配对不发（`StandbyPolicy.shouldHeartbeat` 要求 paired，单测锁） | PASS(模)：`k` 逐条 +300 单调秒、`g_300` 自洽（StandbyPolicyTest 锁"会话中/未配对不发"且回滚变红） | **PASS(真)**：Mi10 生产日志，18:24:17 `probe_start k_796002` →（18:25:08 起锁屏静置）→ 18:29:17 `heartbeat detail=k_796302_g_x_n_wifi_d_0_b_5_w_1_s_1`，`k` 正好 +300 单调秒、`g_x`=本进程首条 ⇒ **"待机一句话都不说"这个原始病灶在真 HyperOS 上已反证**（模拟器那条仍是 `PASS(模)`：熄屏静置 8 分钟里 `heartbeat k_3309 g_x` 准点到达） | ⬜ 缺"连续两条 `k=` 差 >2×300s"的**缺口**实证（本轮核心待证格：缺口才是被冻/被杀的判据，模拟器与"没被冻的 Mi10"都造不出来，只能等米15 或被 `am kill`/低内存真杀一次）→ **10-19 已实证，但成因是"杀"不是"冻"**：同一条 socket 上最后一条心跳 `k_805604`（10-18 21:04:19）之后进程被 MIUI 杀，复活后第一条是 `k_841843`（10-19 07:08:18），**Δk=36239s ≈ 10.07h ≫ 2×300s**，且 `g_x`（本进程首条、无上一条可比）如实标出了"中间换过进程"⇒ 缺口判据在真机上成立、并且能靠 `aexit` 归因。⬜ 仍缺**Doze 冻结**造成的缺口（Mi10 在白名单内出不来，只能米15）——两种缺口的日志形状相同，区分要靠同时刻有没有 `aexit` | PASS（策略层）：会话中 `shouldHeartbeat=false` ⇒ 不产噪声；真实日志里会话期无 heartbeat 待复现确认 |
| QoS 出会话（`qos_tick`/`qos_bad`/`gather_done`） | N-A（WebRtcClient 未创建） | N-A（无 PeerConnection） | N-A（同左） | N-A（同左） | ⬜ 每 10s 一条 + 异常插一条：单测锁（QosAnomalyTest 9 条 + DiagDetailTest clamp），⬜ 真机 64 字节截断与 `x`（无读数）渲染待一次共享实测（本机 `.env` TURN 是占位值、模拟器 ICE 打不通，`ice_failed` 已能出数即是佐证） |
| 补报通路（`detail=at_` + `backlog_drop`） | N-A（无队列消费者） | PASS(模)：15:00 `wake_ok detail=at_…` 走的是"连接前入队、复活后排空"这条路 | **PASS(真)**：18:35 Mi10 在生产服务器补回 11 条 `detail=at_`（此前全天 0 命中），其中死亡窗口内三条待机读数 `screen_off(k_796479)`/`screen_on(k_796562)`/`heartbeat(k_796602)` 全部到达，且**环的 180s 窗口在真机上正好被踩到边界**——`screen_on(k_796424)` 距 down 沿 227s 没被补报（它实时到达过，不是丢数）；⬜ 米15 生产侧的自然样本仍缺（她那台设备是否走同一条补报路径未证） | ⬜ 缺 P0 不被 qos_tick/heartbeat 挤掉的实证（BacklogTierTest 只在 JVM 证了淘汰顺序）；另注意**补报副本与实时行是同一事件的两次投递**，`repro-capture.sh summary` 已分开计数并写进去重口径（t0 有锁），统计次数时不许相加。**另一条补报路径也在真机见光（10-19 07:03:18）**：被杀 10h 后拉起 App，`probe_start`/`bucket_act`/`net_up`/`wake_ok` 四条带着彼此只差 5ms 的 `at_17923645981xx` 一起到达 ⇒ 它们发生在"socket 还没鉴权"那几毫秒，走的是**连接前入队、连上后排空**这条路（与 18:35 那种"死亡窗口重放副本"是两个不同来源，日志里都只呈现为 `detail=at_`，读的时候别混成一种） | ⬜ 缺一次抖动期多批排空（5 批=100 条）实证；`backlog_drop` 需 P0 超 150 条才出现，仍零采样 |

---
维护规则（AUDIT.md §5）：每关一条真机 bug，本表加一行/填一格并登记 gate；新有状态功能上线前必须先有 §表头，格子可以 ⬜，表不能没有。
