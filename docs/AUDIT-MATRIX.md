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
格子判据是**"这一格在服务器日志里真的出过数"**，不是"代码里埋了"——所以本表几乎全是 `⬜`，
这是如实而非漏填：0.2.59 尚未装机，米15 又接不了 adb，唯一能填的路径是发版后她正常用一次。
`⬜` 后的括号写清"缺哪一次采样"，复现后用 `scripts/repro-capture.sh summary` 一次性对照。

| 事件＼状态 | 未配对 | 配对·亮屏待机 | 配对·锁屏待机 | 配对·Doze | 会话中 |
|---|---|---|---|---|---|
| 信令 socket 死（`signaling_down`/`signaling_up`） | ⬜ 结构性不可见：`diag.*` 在未鉴权连接上被服务器丢弃（index.js 的 authed 门），**零采样是设计不是坏**（读日志口径见 AGENTS.md） | ⬜ 缺一次"配对亮屏静置 + 服务器重启"的 down/up 对（v0.2.57 已有 downMs，本次新增 `n=/d=` 网络与 Doze 同屏） | ⬜ 缺米15 锁屏掉线的 down→up 一对（今日 `detail=at_` 0 命中，正是本轮要证的通路） | ⬜ 缺 Doze 进入后的第一条 down（判"Doze 杀 socket"与"网络切换杀 socket"是否同一条路径） | ⬜ 会话期信令死亡是否也补报（`BacklogTiers` P0 配额下理论上必留，待真机日志确认） |
| 屏幕开关（`screen_off`/`screen_on`） | ⬜ 同「未配对」口径：receiver 在跑但报文出不了未鉴权连接 | ⬜ 缺一次非会话期亮屏事件（v0.2.59 起 receiver 不再 gated `if live`，这是本轮新增，未验） | ⬜ 缺米15 锁屏瞬间的 `screen_off`（`live=false` 那条=待机证据链第一环） | ⬜ Doze 期屏幕本已灭，只缺"灭屏后再次边沿"是否被 ROM 吞掉（若无事件=冻结，非埋点坏） | PASS（既有）：会话期 screen_on/off 自 0.2.4x 起在生产日志有采样；共享端已由 `ScreenShareService` 报，StandbyProbe 用 `skipScreenEvent` 避免同一时刻两条真值（StandbyPolicyTest 锁） |
| 网络切换（`net_up`/`net_down`/`net_type`） | ⬜ 未配对期间无通路（同上） | ⬜ 缺 Mi5/Mi10 本机一次 Wi-Fi↔蜂窝切换（本轮唯一能自验的一格，step 7 真机清单第 ③ 项） | ⬜ 缺米15 锁屏后是否报 net_*（与"待机断连"的差分判据） | ⬜ 缺 Doze 断网时 net_down 是否到得了——**到不了本身就是要的答案**（Doze 冻结 binder 回调） | ⬜ 会话期网络切换（`registerDefaultNetworkCallback` 与 ICE restart 是否互相掩盖，待日志） |
| Doze 进出（`doze_on`/`doze_off`） | ⬜ 无通路 | ⬜ 亮屏待机通常进不了 Doze，此格预期长期为空（不许为凑数改成轮询上报） | ⬜ 缺锁屏后 `doze_on` 的时刻与 `signaling_down` 的先后 | ⬜ 缺 `doze_off`（解锁即报；只有 on 没有 off = 进程被冻在 Doze 里） | ⬜ 会话中主动 `isDeviceIdleMode` 取样的对照（当前只在边沿报，会话期无读数） |
| App standby bucket 降级（`bucket_*`） | ⬜ 无通路 | ⬜ 缺一次 `bucket_act→bucket_wset` 边沿（60s 轮询 + 边沿去重，StandbyPolicyTest 锁名字映射） | ⬜ 缺 HyperOS 锁屏后是否降到 `bucket_rare`/`bucket_rst`——**这是米15 待机断网最直接的自证** | ⬜ 缺 Doze 与 bucket 降级的因果顺序（哪条先出现） | ⬜ 会话期 bucket 是否被顶回 active（若顶回，说明 FGS 改变了 bucket，属副作用需知） |
| 进程被杀（`aexit`，AEI 首读） | ⬜ 未配对时不存在"上一轮被杀"的可读语境（且无通路） | ⬜ 缺一次"杀进程→重开 App→连上信令"的 `aexit detail=r=<n>`（API 30 守卫，Mi5 API24/25 不报，须如实标注不可覆盖） | ⬜ 缺米15 被 ROM 杀后首次连上的 `r=`（`REASON_SIGNALED`/`REASON_LOW_MEMORY`/`REASON_USER_STOPPED` 三选一就是最终答案） | ⬜ Doze 与 kill 的区分（此前完全不可知，本轮新增） | ⬜ 会话中不会发生（AEI 只在首次 connected 读一次，设计如此，不追轮询） |
| 心跳缺口（`heartbeat` beat= 单调锚点） | ⬜ 未配对不发（`StandbyPolicy.shouldHeartbeat` 要求 paired，单测锁） | PASS（策略层）：300s 一条、会话中不发、未配对不发——StandbyPolicyTest 逐条锁且回滚变红；⬜ 但服务器侧"连续两条 beat 差 >2×300s"的缺口判据零采样 | ⬜ 缺锁屏待机的完整序列（缺口即"进程被冻/被杀"，无需额外事件） | ⬜ 缺 Doze 后的缺口长度分布（本轮核心待证格） | PASS（策略层）：会话中 `shouldHeartbeat=false` ⇒ 不产噪声；真实日志里会话期无 heartbeat 待复现确认 |
| QoS 出会话（`qos_tick`/`qos_bad`/`gather_done`） | N-A（WebRtcClient 未创建） | N-A（无 PeerConnection） | N-A（同左） | N-A（同左） | ⬜ 每 10s 一条 + 异常插一条：单测锁（QosAnomalyTest 9 条 + DiagDetailTest clamp），⬜ 真机 64 字节截断与 `x`（无读数）渲染待一次共享实测 |
| 补报通路（`detail=at_` + `backlog_drop`） | N-A（无队列消费者） | ⬜ 队列在配对期即在写（DiagBacklog 不筛会话），缺一次"亮屏期断连→重连"的 `at_` 命中 | ⬜ 核心待证：10-18 全天 `detail=at_` 0 命中，本轮改动后必须出现，否则按 §5 先修通路不许改业务 | ⬜ 缺 P0 不被 qos_tick/heartbeat 挤掉的实证（BacklogTierTest 只在 JVM 证了淘汰顺序） | ⬜ 缺一次抖动期多批排空（5 批=100 条）实证 |

---
维护规则（AUDIT.md §5）：每关一条真机 bug，本表加一行/填一格并登记 gate；新有状态功能上线前必须先有 §表头，格子可以 ⬜，表不能没有。
