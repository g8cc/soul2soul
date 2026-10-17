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

状态：`未开始` / `握手中(offer/ice待排)` / `共享中(live)` / `锁屏停格(≤15s)` / `收尾(stopping)`

| 事件＼状态 | 未开始 | 握手中 | 共享中(live) | 锁屏停格 | 收尾(stopping) |
|---|---|---|---|---|---|
| bye / peer.gone | N-A 无会话 | PASS 未接听=上一轮呼叫回声必须忽略（SignalRouter + SignalRouterTest） | PASS PeerEnded 横幅+退出（同上） | PASS stopSession(sendBye) 后迟到=无害 | N-A |
| 断网（peer_lost） | N-A | ⬜ 连接超时路径真机待核 | PASS peer_lost 横幅（SignalRouterTest） | N-A 已停格计时 | N-A |
| 息屏/锁屏 | N-A | ⬜ live 前锁屏不发 screenoff（gated `if live`），观看端对着冻结画面——待真机 | PASS 立即 screenoff+收回授权横幅 | PASS screenOffStop 15s 自动结束（PRD FR-8，"帧数判据误杀"已锁） | N-A |
| 解锁 | N-A | — | PASS v0.2.45：screenon anno+ctl 双通道补发，横幅不再被弱网丢包卡死（AuditRound2WiringTest） | PASS 撤停格计时+screenon | N-A |
| 对方取消呼叫 | PASS 来电通知同步撤销 ID_CALL（v0.2.45，AuditRound2WiringTest） | PASS CallerCanceledBeforeAccept：停铃+提示+finish | N-A 已接听走 CanceledAfterAccept（授权阶段主叫反悔，别让对方数 30 秒） | — | N-A |
| 操控授权中途被收回 | N-A | — | PASS v0.2.45：ctl_denied→applyControlMode(false) 退出死模式+横幅说明；controlMode 写入全文件收敛一处（AuditRound2WiringTest） | PASS 锁屏收回专用 DeniedScreenOff（CtlConsentMachineTest） | N-A |
| 通知栏动作按钮可见性 | N-A | — | PASS v0.2.46：静音/操控/结束三键全带图标——icon=0 纯文字按钮在折叠态不渲染，MIUI/HyperOS 常驻通知默认折叠（真机实锤"按钮时有时无"；t0 addAction(0 守卫） | N-A | N-A |
| 快速重拨/服务复用 | ⬜ 双 start 是否丢新 projection（SSS 复位段）待真机复现 | — | — | — | PASS 迟到通知动作 webRtc==null→stopSelf（SSS:90）；stopping 幂等 |
| 信令排队/回放 | PASS pendingSignals 入队→open 后回放并清（SSS:175/196） | — | — | — | PASS 三处清空：start 复位/回放后/onDestroy（SSS:122/197/223） |
| 进程被杀 START_STICKY | ⬜ intent=null 重建路径（同 §1 孤儿球，一并真机核） | — | — | — | — |

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

## 4. 权限引导（通知/麦克风/悬浮窗/无障碍）

| 权限＼场景 | 首次授予 | 永久拒绝 | 系统重置为"每次询问" | 会话中途撤销 |
|---|---|---|---|---|
| 通知 | PASS 引导存在 | PASS areNotificationsEnabled 预判 + SecurityException 兜底（showIncoming/Recall 不崩） | ⬜ 待真机 | ⬜ 常驻会话通知被撤是否影响 FGS（Android12+ 语义）待真机 |
| 麦克风 | PASS 时机前置到切语音（v0.2.33 实锤修复） | PASS #31 解释弹窗+设置直达 | PASS #31 重置路径引导 | ⬜ 采集中被撤：仅下次打开感知（AudioRecord 报错面未真机核） |
| 悬浮窗 | PASS 引导+类型回退（v0.2.22 Android7 TYPE 锁定） | PASS addView 失败分支还球（c107105 m5） | 同左 | ⬜ 已挂窗被系统回收、下笔表现（同 §1，待真机） |
| 无障碍 | PASS 引导 MIUI 路径+反馈不依赖 toast（#13） | PASS ctl_need_acc toast + 授权对话框乐观写回滚（CtlConsentMachine.setTarget） | ⬜ accConnected 区分真撤销/被冻已实现（#33 代码侧闭环），真机复验仍挂 | PASS ctl_error(reason) 横幅分级+4s 节流回灌（routeData ControlFailure + CtlConsentMachineTest） |
| 电池豁免 | PASS 引导对话框存在 | — | ⬜ askedBattery 单向闩：拒绝后不再追问是否够用（待产品定，暂不算 bug） | N-A |

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

---
维护规则（AUDIT.md §5）：每关一条真机 bug，本表加一行/填一格并登记 gate；新有状态功能上线前必须先有 §表头，格子可以 ⬜，表不能没有。
