# 更新日志

倒序。锚点为 git 短哈希（`git log --grep='v0\.2\.'` 可复核）。App 内更新通道自 v0.2.16 起可用。

## v0.2.63 — 2026-10-19（本批，已上通道）

- **一次性把 0.2.59–0.2.63 五代的东西带上更新通道**：通道此前停在 `versionCode 60`（0.2.59 `6210c83`、0.2.60 `4df1ff7` 各单独发过），0.2.61/0.2.62 只进过 repo 没单独上通道，本版把它们一起带走。远端 `version.json` = `{63, "0.2.63", sha256 379f194b…}`，HTTPS 取包 200，本地构建产物 sha256 与线上逐字节一致。米15 只能靠这条通道更新，而它是唯一接不了 adb 的那台
- **v0.2.59 全量遥测版**（`568a63d` `1d8eaf9`）：把传感器从会话期搬到待机期——`StandbyProbe` 收 SCREEN_ON/OFF、`ConnectivityManager` 默认网络回调（`net_up/net_down/net_type`）、Doze 进出、app standby bucket 降级、wakelock 获取结果，外加配对期 300s 一条 `heartbeat`（**连续两条 `k=` 差 >2×300s 本身就是"进程被冻/被杀"的判据**）；`ApplicationExitInfo` 在复活时报上一轮死因（`aexit r_… i_… a_…`）；`diag.session` 的 `path`/`proj` 真正填上（此前服务器白印 `_`）；会话期 QoS 从"只在 logcat"变成每 10s 一条 `qos_tick` + 异常插 `qos_bad`，`gather_done` 带 host/srflx/relay 候选计数（TURN 分配失败=`r_0`，此前这条判据不存在）；补报队列 `DiagBacklog` 改分级淘汰（默认 P0，只降级 `heartbeat`/`qos_tick`）+ 有界 flush + 入队离开主线程
- **v0.2.61 终止原因分名**（`d97ee5a` `1a493b1`）：`projection_stopped`（系统撤授权）先占 first-wins 槽、`remote_bye`/`peer_gone`/`call_canceled` 各有各的名分、`stop_tap detail=src_…` 回答她点的是哪个停止入口。≤0.2.60 的日志里 `local_stop` 同时是"她按的"与"ROM 撤的"，只能靠同行 `proj` 补救
- **v0.2.62 修掉"连上几秒就自动断开"的两个自因**（`d370a3d` `fc4814c` `7519bd2`）：带宽估计器爬坡期的 300kbps 初值被当实测 → 3 连拍降档 → 同一 `ScreenCapturerAndroid` stop+start → Android 14 判第二次开采集并撤销授权 → 4.5s 死；改法是爬坡期不参与降档判据、14+ 只降码率不动采集尺寸。帧率改累计 `framesSent`/`framesReceived` 区间相减（这版 libwebrtc 没有 `framesPerSecond`，直读的 -1 曾被 `<=0.0` 判成 0 帧 = 线上 6 条假 `video_stall` 的成因）；`qos_bad` 的 `b=fps0` 撤除，0 帧只留 `video_stall` 一个真值来源
- **测试与口径**（`f249a29` `65bf01d` `5ef1a0c` `4f98deb`）：S10 把"高于 minSdk 的系统 API 必须各自带版本闸门"锁在**调用点所在函数体**上（漏闸门不是少一条读数，是 Mi5 直接崩、此后那条通路整条消失）；扫出并修掉四条"固定行数窗口对否定式判据只贴前半段"的空锁（含把已废的"锁屏 15 秒自动结束"写回分支而用例照绿那种），另加两条 t0 守卫盯着这个形状；`docs/TESTING.md` 立坑㉚
- **诚实边界**：本版是客户端行为，服务器一行未动。通道上有包 ≠ 装了 —— 依赖米15 的格子在 `diag.version code=63` 出现之前一律不许升 PASS；Android 7/8（本机 Mi5）上 `bucket_*`/`aexit` 零采样属设计（API 28/30 门槛），心跳 `b=` 恒为 `x`
- ⚠️ 本文件在 v0.2.27 与 v0.2.55 之间**长期没记账**（0.2.28–0.2.54 这段没有条目，别把它读成"那半年没改动"）；上面的锚点用 `git log --grep='v0\.2\.' --oneline` 可逐条复核

## v0.2.27 — 2026-10

- 观看端沉浸全屏：通话连通后 5 秒无操作，控件连同状态栏/导航栏一起收起，整屏只显示对方画面；轻点画面同步唤回控件+系统栏，侧边滑入可临时呼出系统栏；画中画返回全屏自动恢复联动。Android 7/MIUI 上 androidx WindowInsetsControllerCompat 的 hide(systemBars) 会吞掉 FULLSCREEN 位（实测只隐藏了导航栏），API<30 改为直写 legacy systemUiVisibility flags
- 观看端两侧空隙改纯黑衬底（影院黑边语义）。曾实现"远端画面 1/12 降采样模糊放大"衬底，但糊图本质是前景内容的鬼影，两台手机屏幕比例不同时看起来像两块屏幕重叠，观感更差，故回退为纯黑
- 开场尺寸缓存：上次作为观看端收到的远端视频尺寸存入 Prefs，新会话 onCreate 即按缓存套用等比布局，消除首帧尺寸事件到达前（约 1~2 秒）match_parent 被合成层铺满拉伸的裁切闪跳，事件到达后再校正
- 系统栏收起/唤回改变根容器高度时，通过 OnLayoutChangeListener 重算视频等比矩形，沉浸态下画面保持精确居中

## v0.2.26 — 2026-10

- 啸叫根治：设备支持时改走系统 DSP 硬件回声消除（此前刻意关闭 HW AEC、只靠软件 AEC3，而 MIUI 上报播放延迟不准 → AEC3 参考对齐失败 ≈ 无 AEC →「扬声器→自己麦克风」正反馈，两台手机隔空也尖锐啸叫不断变大）；硬件 NS 仅与 HW AEC 同链路启用，纯软 AEC3 时不再叠加硬件 NS（破坏线性对齐）。双端都要更新到本版才完整生效
- 观看端底部裁切修复：letterbox 黑边只画在 SurfaceView 合成层内，MIUI 对视频 Surface 做"铺满拉伸"合成把黑边连同画面底部裁掉约 20%；改为收到远端视频尺寸后直接把渲染 View 缩成视频等比矩形并居中（视图比例=内容比例，任何 ROM 合成都裁不掉），正确显示下观看端会出现左右黑边（16:9 看 20:9 属物理必然）；涂鸦/操控触点走 fitRect 同公式，缩放后仍对齐

## v0.2.25 — 2026-10

- `11ca257` 远程操控坐标漂移修复：真实屏幕尺寸改走 `Display.getRealSize`（`currentWindowMetrics` 在 API 30+ 可能返回扣掉系统栏/分屏的应用窗口矩形，与 dispatchGesture 的整屏坐标系混用产生稳定纵偏"总点在上面"）+ 0×0 兜底链；采集/注入/悬浮层统一同一套整屏像素坐标；会话生命周期收口；StrokeMapping 新增用例
- 主页 UI 修复：「给TA留言」按钮改 Material3 描边样式（此前 MaterialButton 忽略 `android:background`，粉底粉字=看不见字的色块）；配对码弹窗重做（引导文案 + 圆角大字号居中 6 位输入框，替换裸 EditText）

## v0.2.24 — 2026-10

- 离线留言（文字+语音）随本批发版：`06e2b13` 服务器端 MessageBook 留言箱（每人 20 条/7 天 TTL/限速 1s/阅后即删/解绑双向清空，10 条单测）+ WS msg.* 接线 + HTTP 语音上传下载（配对令牌鉴权，仅收件人可取）+ 黑盒 16 项场景；`7c000be` 客户端留言页（气泡列表、文字、30s 语音录制/播放、打开即阅）+ InboxStore 进程留言箱（6 条单测）+ 主页「给TA留言」未读角标 + 对方不在线弹窗引导；协议见 docs/PROTOCOL.md §2.1/§2.2/§2.4
- `4ce2928` 线上 hotfix 回灌：配对输码全角/空白归一化进 PairCodeBook + enter 上限改 env 可配（线上 30min/10 从此是配置不是源码漂移）；生产服务器已按此部署（2026-10-06 切换，无 naive 覆盖）
- 真机联调修复 ×2：Android 7 悬浮窗类型回退（TYPE_APPLICATION_OVERLAY 是 API 26+，Mi5 上被 WindowManager 直接拒绝导致涂鸦回显"悬浮窗无效"；旧系统改挂 TYPE_PHONE）；采集/手势注入/悬浮层统一改用真实显示尺寸（resources.displayMetrics 是 app 视角，全面屏上少导航条/挖孔一截，导致视频裁切+触点整体偏移"差了一截"）

## v0.2.23 — 2026-10（本批）

- 测试 harness 体系：`scripts/test.sh` 分层（T0 静态 / T1 Android JVM / T2 Server 单测 / T3 协议黑盒 / T4 E2E 门控），T0–T3 默认全绿才算过
- 纯逻辑提取 ×8（行为零变更，各自独立提交）：GestureIntent、VersionManifest、SignalEnvelope、turn.js、OverlayGestureEngine、CtlConsentMachine、SignalRouter、PairCodeBook
- 用例从 12 条扩到 131 条（Kotlin 85 + node 14 + 黑盒 32），锁定 0.2.18–0.2.22 全部真机回归
- 文档：新增 docs/PROTOCOL.md（线上协议参考）、docs/TESTING.md、AGENTS.md、本 CHANGELOG；SPEC.md §3 加勘误指针

## v0.2.22 — 2026-09

- `886dd16` 语音状态可视化：连通后默认开麦不再是隐形状态（通知正文/麦克风 chip 明说开没开）

## v0.2.21 — 2026-09

- `9162367` 多指污染修复本体：画笔/操控手势只认首指（第二根手指不再把线甩走/污染手势点序）
- `e8009f4` 自检遥测（app.diag 上报机型/权限矩阵）+ 电池优化豁免引导 + 全文件审计修复（含授权状态跨会话残留）

## v0.2.20 — 2026-09

- `98c59a6` 操控授权对话框（点共享通知即弹，HyperOS 折叠 FGS 通知按钮的替代入口）；远程"返回"打在自家界面上改执行 HOME 不再拆通话；本端返回键防误退

## v0.2.19 — 2026-09

- `6d99a48` 边缘手势改走 performGlobalAction（系统级真动画）；操控反馈改指尖光晕+450ms 尾迹；长按 650ms 唤菜单、8s 自动收回；被拒原因经 ctl_denied 回显横幅

## v0.2.18 — 2026-09

- `ff24266` 回退 FLAG_SECURE（MIUI 采集黑块回归）；涂鸦双影治理（本地笔迹 600ms 快速淡出，持久墨迹只留共享端回显）；表情面板自动收纳归零；操控授权会话级收回

## v0.2.17 — 2026-09

- `b0aa6d8` 版本号恢复常规展示（去掉本机码诊断后缀），运行时自检转后台遥测

## v0.2.16 — 2026-09

- `cd2822c` 修复观看端笔迹双影（一条线画成两条）；共享端悬浮标注层实验性加 FLAG_SECURE（后在 0.2.18 回退）

## v0.2.15 及更早

- `bbca4ab` v0.2.15 工具条两行重构 + 操控持久横幅
- `9e0a2d4` v0.2.10 两轮对抗审计修复（隐形笔迹全屏窗 / PiP 困在操控模式 / 收回授权竞态等 9 项）
- `a85f406` v0.2.9 远程控制 + 满屏互动特效 + 涂鸦不挡操作
- `03fede9` v0.2.6 深度优化轮 + VP9 优先修复
- `5f268c1`/`56a195a` v0.2.4 自动更新 / 表情 / WiFi 高性能锁 / 配对全链路
