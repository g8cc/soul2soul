# DIAG-READINGS.md — 读服务器日志的口径（v0.2.59 待机遥测起必须知道）

从 `AGENTS.md` 搬出（10-23 瘦身：AGENTS 只留"每次都要守"的规则，读数口径按 needing 加载）。
**改任何埋点、或要从 `diag.*` 下结论之前先读这里。** 锚点行号会腐烂，本节全部按"事件名 + 版本"定位。

- **`diag.session reason` 的版本分界是 0.2.61**（10-19 抓到四处"同一个字符串掩盖两种死法"，全部已在 repo 修掉）：
  ≤0.2.60 里 `local_stop` 同时涵盖"她按了停止"与"HyperOS 撤销录屏授权"（`MediaProjection.Callback.onStop()`
  不占 first-wins 槽，被收尾那条 `local_stop` 覆盖），共享端被对方挂断则只剩 `local_close`。
  0.2.61 起可直接读：`projection_stopped`＝系统撤的授权、`remote_bye`＝她挂的、`peer_gone`＝只是信令掉线
  （媒体可能本来就好）、`call_canceled`＝主叫在授权阶段反悔（`setupMs=-1`，从没连过，别读成"连上几秒被挂断"），
  另有 `diag.event stop_tap detail=src_main_page|src_notify` 回答"她点的是哪一个停止入口"。
  现网补救读法（≤0.2.60 唯一可用的一列）＝同一行 `proj`：`stopped`⇒被撤销、`live`⇒她按的；`proj=_` 的会话永久无法判。
- **`reason=projection_stopped` 到 0.2.63 为止；0.2.64 起它根本不再产生**：系统撤销录屏授权（她锁屏省电、或在系统隐私提示里点"停止录制"）
  从这一版起**不是会话终止**，而是"画面停·语音与信令续·一键重开"。判据换了一条：
  `diag.event projection_revoked`（detail `keepalive`）＝画面被撤而会话还活着；`projection_resumed`＝她重开成功；
  `projection_reopen_fail` 的 detail 分两种出路（`no_data`=通知没把 Intent 带回来，`startCapture`=新 token 起不来采集，只能再授权一次）。
  观看端侧同步换了语义：撤授权收到的是 DataChannel `k=videooff`（横幅「对方画面已暂停·语音继续」+ 暂停 `video_stall` 判定），
  **`screenon`（解锁）从这一版起不等于"画面回来了"**，只有 `videoon` 能收横幅——解锁就撒谎比卡在旧横幅更糟。
  ⚠️ 两个方向都不许读反：拿 ≥0.2.64 的日志找 `projection_stopped` 会得出"这版没埋"，它只是不再该出现了；
  反过来把收尾那行的 `proj=stopped` 读成"她挂了"也是错的——`proj` 只描述收尾那一刻的采集状态，不回答"中途有没有被撤过"。
- **`diag.*` 只在已鉴权连接上打印**（`server/src/index.js` 的 `if (!ws.authed) break`）。
  所以**未配对期间的日志必然是空的，这不是埋点坏了**；判"某事件零采样"之前先确认那一刻两端已配对。
- **`detail` 里的 `=` 会被 `safeTag` 磨成 `_`**：补报的真实时刻要 grep `detail=at_`，写 `detail=at=` 是一个不存在的键
  （t0 有守卫盯着 `docs/PROTOCOL.md` 与本仓库脚本别改回去）。
- **一轮复现后先看 `bash scripts/repro-capture.sh summary`**，它按 `docs/PROTOCOL.md` 的
  `DIAG-EVENT-NAMES` 清单逐条列"响了/没响"，不再靠人肉逐条 grep。清单是唯一权威：
  新增事件名不写进那一行，t1 的 `DiagEventRegistryTest` 直接红。
  summary 会把**实时行**与**补报副本**（带 `at_`）分开标出，因为补报是"至少一次"投递：
  同一事件可能在断网后既有一条实时、又有一条 `at_` 副本。数发生次数用 `总数 - 副本数`，
  或直接数不同的 `k=`（10-18 真机那轮 heartbeat 合并计数 4 条、其实只跳了 3 次）。
- **`diag.*` 的 `screenOn` 列是"发送那一刻"的采样，不是事件时刻的状态**（`SelfCheck.kt:138-140` 读 `isInteractive`，事件名来自广播 action，两者可以差零点几秒）。10-19 09:01 实测过一条 `screen_off` 带 `screenOn=true`、0.73s 后紧跟同 `k` 的 `screen_on`＝MIUI 秒级闪灭。**判"当时屏灭没灭"要看事件序列本身**，不许拿这一列自证。
- **两个 v0.2.59 传感器在 Android 7/8 上结构性缺席**（minSdk=23，Mi5=API24）：`bucket_*` 要 API 28、`aexit` 要 API 30，
  源码各自起手早退。所以那台机器上"零 `bucket_`/零 `aexit`"是设计，不是通路坏了；更要紧的是心跳里的 `b=` 会恒为 `x`
  （`lastBucket` 保持 -1）——`x` 是无读数，不许读成"0 档"或"档没变"。这条判据由 `StandbyTelemetryTest` S10 锁在**调用点所在函数体**上
  （把闸门挪进别的函数实测变红）。
- **待机/换网时 `signaling_down` 一直早于 `net_down`**（10-18/10-19 三次真机采样：早 52ms、54ms、4.86s，跨三个数量级）⇒ socket 层是更早的传感器，`net_*` 只是晚到的旁证，"断网杀了信令"这句在日志里从没被直接证实过。另外 0.2.58 起 `signaling_down` 自己也能经补报到达（`detail=at_`；10-18 18:35 Mi10 第一次，10-19 又两轮含灭屏那次），0.2.56 那格"到不了服务器"的死角已闭合。
- **补报队列的分级是"默认保护"，不是 P0 白名单**：`BacklogTiers.tierOf` 只把 `heartbeat`(P1) 与
  `video_stall`(P1) 降级、`qos_tick`(P2)，**其余一切事件名（含 `signaling_down`/`screen_off`/`probe_start`/`stop_tap`）都是 P0**，
  新加的埋点忘了登记也默认受保护。读日志时不许按"某个手写清单里有没有这个名字"来判断某条证据能不能丢
  （10-19 复核时 PROTOCOL 正是写成了白名单口径，已改正并由 `BacklogTierTest` T9/T10 锁住默认方向）。
  `video_stall` 是 10-19 双真机轮**有据可依地加进去的第三个降级名**（不是白名单口径的回潮）：真机静止画面下
  它最短 40.0s 就能再凑一条，一通 21 分钟共享吃 9 格；留在 P0 的后果不是判错而是把死亡现场（`ice_failed`/`aexit`）
  挤出配额，而 stall 的时刻线本来就能从 `qos_tick` 第 7 格复原 ⇒ 判"这条能不能丢"的口径始终是**重复那几条是否携带新信息**，
  不是它在不在这份例外清单里（清单本身由 T9 从源码扫出来对账，加名字必须同时在用例里交代理由）。
- **"强制手段造出来的只是可达性测试"这句话有适用范围，别一刀切**：判据是**脚本触发的是"事件"还是"ROM 的决策"**。
  `svc wifi disable` 在真机上触发的是事件本身，而断网→换网→socket 死仍由系统走它自己那条路（Mi10 实测出
  `net_type from_wifi_to_cell` 与 `signaling_down`，这条是真读数）；反过来 `cmd deviceidle enter`、
  `am set-standby-bucket` 触发的是**决策**（Doze 该不该进、bucket 该不该掉），ROM 自己会不会这么做脚本管不了，
  对应格子必须保持 `⬜`。
- **`video_stall` 在 0.2.61 及以前是"读不到帧率"的别名，不是卡死**：这一版 libwebrtc 的
  `outbound-rtp`/`inbound-rtp` 根本没有 `framesPerSecond`，而观看端判据写的是 `inboundVideoFps <= 0.0`
  ⇒ 直读得到的 -1 每 10s 加一格，30s 后必然告警。线上那 6 条（`ice=CONNECTED`、`rttMs=84/59/91`、
  链路明明是好的）全部属于这一类。**读旧日志时把 `video_stall` 当成"画面卡住"是错的**；
  v0.2.62 起帧率是累计 `framesSent`/`framesReceived` 的区间相减，"没读数"走独立分支。
- **"画面死了"只有一条判据：`video_stall`，但它必须与共享端同一刻的 `qos_tick` 第 7 格成对读（v0.2.63 起）**：
  单看 `video_stall` 会误判——共享端画面静止时编码器不出新帧，观看端连续 3 窗口测到 0 帧是真的 0 帧，
  而画面没坏（10-19 本机 established 会话实测：同刻 sharer 第 7 格也是 0）。
  **密度以真机为准，别拿模拟器那句"不刷屏"当尺子**：本机双真机那轮观看端 20 分钟报了 9 条、最短间隔
  **40.0s**（连续三条都是 40s 整）——通知、时钟秒针、MIUI 任何一闪都会让帧回来一次、streak 清零后 30s 再凑一条，
  所以"40s 一条"是静止画面的正常密度，绝不许把 `video_stall` 的**数量**读成"卡了几次"。
  它在补报队列里的档位：#78 前是 P0、**现为 P1**（与心跳合用 25 格）——一通 20 分钟能吃掉 9 格配额，
  降级的理由不是"它不重要"而是"它的时刻线本来能从 `qos_tick` 第 7 格复原"。
  三条分法：两端都 `0`=源头静止；sharer `>0` 而 viewer `0`=媒体真断；任一端 `x`=无读数、什么都不判。
  `qos_bad` 里的 `b=fps0`（单窗口 0 帧）已撤除——静态画面单窗口 0 帧本来就是常态（采集没变化就不出帧），双端每 10s 各插一条、一分钟 12 条把 P0 补报配额灌满，
  真异常反而被挤掉。所以 0 帧看 `video_stall`（连续 3 个完整 10s 窗口，且排除本端停帧与对方锁屏；恢复那一条 `video_resumed`
  至今**没有任何真机或模拟器样本**，判恢复只能看下一条 `qos_tick` 第 7 格，别等那条永远不来的），
  `qos_tick` 第 7 格只是原始帧率读数（`x`=没测到，不许当 0 读）。
- **"她画的我看不到"有三种成因，先别怀疑通路（10-19 实测）**：① 收笔后 `StrokeColors.FADE_MS=2000` 淡出，
  截图晚于 2s ⇒ diff 恒 0（判据要拍在"手指还按着"的时刻，书写中永不淡出）；② 通路真坏（看 B 侧
  `annotation dropped` / channel null）；③ **宿主 App 声明了 `HIDE_OVERLAY_WINDOWS`**（Android 12+
  `Window.setHideOverlayWindows`）——设置页、部分 ROM 桌面、银行类 App 前台期间，框架把别人所有
  `TYPE_APPLICATION_OVERLAY` 强制隐藏：窗口 attached、`mDrawState=READY_TO_SHOW`、也在 draw，但
  `isVisible=false` / `isReadyForDisplay()=false` / `alpha=0.0`，一帧都不合成，涂鸦球一起消失。
  机器可查形式：`dumpsys window windows | grep mForceHideNonSystemOverlayWindow=true`
  （注意窗口头是 `Window{hash u0 pkg}`，grep `Window \{` 会静默 0 命中，10-19 我就据此误判过"窗口不存在"）。
  排除 ③ 之前不允许下"通路坏了"的结论；这一格的产品处置仍是 `⬜`（见 `docs/AUDIT-MATRIX.md` §1 末行）。
- **"连上几秒就自动断开"在 Android 14+ 上有一个自因**：带宽估计器爬坡期把 `availableOutgoingBitrate`
  钉在 300kbps 初值（实测连读两拍 300、触发降档那一刻已是 5.38Mbps）→ 3 连拍降档 →
  在同一 `ScreenCapturerAndroid` 上 stop+start → 系统判"第二次开采集"撤销录屏授权 →
  `reason=projection_stopped`。米15 那两通 14.1s/16.1s 与 Mi10（Android 12 容忍 token 复用）能撑 155s
  的设备差异由此解释。v0.2.62 起 14+ 只降码率不动采集尺寸，画质档与采集尺寸正式分家。
- **`heartbeat` 的缺口本身就是判据**：连续两条 `k=`（elapsedRealtime 秒，单调锚点）读数差 > 2×300s 说明进程被冻或被杀，
  不需要额外事件；日志侧这一格渲染成 `k_…`（safeTag 磨平等号与逗号）。设备墙钟不可信（实测 Mi5 慢 ~10.5h），任何"间隔"都不许用 wall-clock 相减。
- **"待机为什么断"要两条读数一起定：最后一条 `heartbeat` 的时刻 + 复活时那条 `aexit` 的 `a=`**，只看 socket 的 `code=1006` 会把被杀误读成断网。
  10-19 Mi10 实测：锁屏静置 2.5h 里心跳 33 条准点 `+300`（`w_1 s_1 b_5`，即锁在、socket 在、档没动），21:07 之后整夜零读数，
  复活时 `aexit r_10_i_125_a_596` 与 ROM 侧 `dumpsys activity exit-info` 的 `21:07:09 reason=10 (USER REQUESTED)` 到分钟对齐
  ⇒ 结论停在**"框架被要求停止进程"这一层**（不是 pong 超时、不是换网）。心跳一直准、却突然断崖，本身就是"被杀"的形状。
- **`aexit` 的 `r=10` 判不到是谁下的手**：10-19 在模拟器上做补报实验时执行 `adb shell am force-stop`，复活报的就是同一条
  `r_10`。所以 adb force-stop、她从最近任务划掉、ROM 省电代劳，三种来源在 AEI 里都是 `reason=10 (USER REQUESTED)`。
  上面 Mi10 那次能归因到省电，靠的是**另一侧的 `am_kill … due to AutoPowerKill` dump**；米15 接不了 adb，
  **单凭 `r=10` 不许写"被 ROM 杀了"**，那一格保持 `⬜`。
- **模拟器出数只算"通路证据"，不算"真机验证"**：`docs/AUDIT-MATRIX.md` §8 里 `PASS(模)` 是故意留的记号——
  它证明"这个埋点会出数、格式对"，不证明"HyperOS 上也会出数"。强制手段（`cmd deviceidle enter`、
  `am set-standby-bucket`、`svc wifi/data disable`）造出来的是**可达性测试**，ROM 的自然行为仍属未采样，
  对应格子必须保持 `⬜`。同理 `path`/`proj` 的 `-` 是"发了但当时无值"（如 `ice=FAILED` 的会话从没选路），
  只有 `_` 才是"没发"——读日志时把这两种混为一谈会得出完全相反的结论。
- **`summary` 的合并计数会骗人，先按 deviceId 分账**：10-19 那轮"heartbeat 33 条"里 28 条属于本机 Mi10 的旧身份、只有 1 条属于米15（`~/s2s-repro/20261019-174950`）。同一轮里"待机遥测只在米15 出数"这个结论成立与否，取决于你把哪一行的数字算到了谁头上。
- **`heartbeat` 的 `g=x` 与 `k=` 缺口都不等于"进程被冻/被杀"**：`lastBeatSec` 只在心跳**真的发出**时才置位，而会话期间 `shouldHeartbeat` 整段抑制 ⇒ 跨一通电话后的第一条必然报 `g=x`，缺口也只需等于通话长度（10-19 Mi10 那条 `g_1200` 跨的正是一通 15 分钟共享）。**判"被冻"要的形状是"无会话、无事件、心跳整段消失"**；两种死法在日志里靠"复活批次里有没有新的 `aexit`"分开——有（`r_10_i_125_a_21`）=进程被停，没有而是补出 `doze_off`+`screen_on`（且锁屏那段连 `screen_off` 都没发出来）=被冻。
- **`close code=1006` 要先减掉"被顶替的旧 socket 的死亡通知"**：`replaced old connection` 之后必定在 **+30.003s** 出现一条同 deviceId 的 1006（`aliveFor = oldAliveFor + 30003`，且前面**没有** `pong 超时 terminate`），那是服务器 `old.close()` 的余波，不是一次新的断连（10-19：27 条 1006 里 2 条属此类、9 条前面有 pong 行）。数断连之前不减，就会把一次重连算成两次故障。
- **`app_exit why=destroy` 自 commit 3f8797b 起只以补报行出现（`detail=at_…_why_destroy`）——该修复随 0.2.64 上了通道**（≤0.2.63 的现网仍是旧行为，在那台版本上"0 条 app_exit"是预期而不是修复失效）：旧版走乐观发送而 `onDestroy` 三行之后就 `close()`，帧还躺在 socket 缓冲里就被关掉——10-19 的铁证是 17:48:10 两端同时留下 `code=1000 reason=bye` 而 `app_exit` 全天 0 条。改进入队后代价是"到达很晚（她下次打开 App 才到）或永不到（再也不打开）"，所以**读旧日志时 `app_exit` 缺席不携带任何信息**，判"她主动退出"要用 `code=1000 reason=bye` 那一行。
- **`echo_ack` 量的只是"收笔 → 她画布落墨"这一跳，不含视频管线（0.2.65 起）**：ack 与笔迹走**同一条 anno 通道**（要测的就是这条路的排队+往返；走可靠 ctl 会测到另一条队列的队头阻塞，得出"很快"的假读数），
  所以 `e=<ms>` 是观看端**交棒时刻的下界校验**（ack 都到不了，视频更到不了），**不是**"回显总延迟"——采集/编码/网络/解码/渲染那几段一概不在里面。
  `detail=e_,x_,m_,p_` 里 `m`（miss）与 `p`（pending）**大于 0 是正常**（anno 是丢包通道，600ms 兜底会接手），不许读成"功能坏了"；`echo_ack` 这个事件在补报队列里是 **P1**（重复采样，单条丢掉不丢结论，p50/p90 由整序列给出）。
  两端版本必须一起看：**0.2.64 及以前的对端既不回 ack 也不认 `k="a"`**（`SignalRouter` 的 `else -> None`），那一版 `echo_ack` 整段缺席是**版本差**，不是埋点坏了。
  ⚠️ **但 `p` 一路均匀上涨（每笔 +1）而 `m` 恒 0、`e` 每笔都新鲜，是 1da0158 之前那个 0.2.65 包自身的 bug，不是丢包**：ack 已把条目 remove，300ms 的收笔补发又在 `putIfAbsent` 上登记成功，而共享端早已提交（`end` 幂等 null、不会再回第二个 ack）⇒ 每笔留下一条永久等待。
  所以 `p>0` 有两种成因，**先对提交再下结论**：修后的包才允许按"真还有几笔在等"读 `p`；旧包里的 `p` 只等于"已画过的笔数"（到 32 后还会开始把真等待的笔迹挤出账本、把 `m` 读成假故障）。
  同一版还有两个读数口子要知道：共享端入口球 `BALL_DIM_ALPHA` 从 0.15f 改成 **0f**（可见即被采进视频=第二个铅笔），所以"她看不到画笔入口"不再等于"入口坏了"，三条入口（摇一摇/隐形命中区/通知动作）里只有第一条在 HyperOS 折叠通知下稳定。
- **`HANDOVER_FALLBACK_MS=600` 是"升级期两版混跑时重叠窗口的上界"，不是延迟预算**：对端 ≤0.2.64 时不回 ack，观看端只能靠这条兜底淡掉，而旧共享端几百 ms 就把副本采进视频 ⇒ 本地可见时长 = 600 + `HANDOVER_FADE_MS`150 = **750ms**，与 0.2.64 自己的 600ms 同量级（原 1500 是它的 2.5 倍，比旧版明显更糟，而这一格本机结构性测不到：screencap 单机 0.6~1.5s/帧、双模拟器视频 ~0.5fps）。
  代价的真实形态：`e` 超过 600 的那些笔会"本地线先淡掉、成品过一会儿才在视频里出现"（实测 13 条里 1 条超 600，为 1236ms）——**这不是坏了**，读日志时别把 `e>600` 当故障。要动这个常量前先想清楚是在改观感上界，不是在改超时。
