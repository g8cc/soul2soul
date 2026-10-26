# 更新日志

倒序。锚点为 git 短哈希（`git log --grep='v0\.2\.'` 可复核）。App 内更新通道自 v0.2.16 起可用。

## 工作区未发布修复（2026-10-26）

- **留言通知声音迁移与失败重试**：`msg2` 在部分 ROM 上仍可能沿用静音渠道状态，留言渠道迁移到全新的 `msg3`，并删除旧 `msg`/`msg2` 渠道；通知权限未开或系统 `notify()` 异常时，只有投递成功才记录 `shownSig`/`alertedIds`，后续同步可重试。该修复尚未随版本号 0.2.66 发布，HyperOS/MIUI 实际声音仍待真机验证。

## v0.2.66 — 2026-10-24（本批，已上通道）

发版四证（本轮实测，不是抄上一版）：
- 远端 `version.json` = `{66, "0.2.66", sha256 afeec77d…af23}`，同一个 sha256 **三处逐字符一致**——本地 `dist/Soul2Soul-v0.2.66-release.apk` 的 `shasum -a 256`、服务器上 `sha256sum soul2soul-latest.apk`、清单里的 `sha256` 字段。
- `apksigner` 报证书 SHA-256 仍是 `ae7fb8ec4f610e4c8f111c6913726e63f231b6384c3bb116a253fba322f53e4f`（与 0.2.64/0.2.65 **逐位相同** ⇒ 已装的手机是覆盖安装、不用卸载，也不会抹掉 `deviceId`），v1/v2 双签 `true`（v3 `false`，与历史各版一致）。
- `aapt dump badging` 读回 `versionCode='66' versionName='0.2.66'`——不是 BuildConfig 常量被内联留下的旧值（脚本内置 `:app:clean`）。
- HTTPS 分段取包回 `HTTP/1.1 206 Partial Content`，`Content-Range: bytes 0-1023/69773886`，总长与本地 dist 文件字节数一致。

额外一格通路证据：产物 `classes3.dex` 里按字节能搜到 `msg2`（1 处）与「留言提醒」（1 处）。这只证明**代码确实进了包**，不证明她听到了声音。

### 本版内容

- **留言提醒改出声（`a5af868`，用户 10-23 改口径「不要静默了」，推翻 10-20 的「静静躺在通知里」）**：渠道**只能换新 id** 才生效——Android 8+ 已建渠道的声音/档位在系统侧持有，`createNotificationChannel` 只补建不覆盖，沿用 `msg` 等于装过 0.2.64/0.2.65 的手机永远不响。所以 `msg` → `msg2`=`IMPORTANCE_DEFAULT`（出声、**仍不弹横幅**，这次只要求有声）+ 显式 `setSound`，并 `deleteNotificationChannel("msg")` 删掉旧静音渠道（否则设置里留两条同名「留言提醒」）。`setOnlyAlertOnce` 不再焊死 `true`，改由"这批未读里有没有从没响过的 id"决定——读掉一条后剩下那条重排、留言页切走再切回来，都不许催第二遍。26 以下（`minSdk 23`，Mi5 这台）没有渠道概念，声音挂在 Builder 上，否则那条路径仍然彻底静音。锁：`UnreadNotifierTest` M4~M7（原 M4 锁的是"不许响"，口径翻转后重写）+ 9 种抽法逐条实测各自变红。
- **更新提示每个远端版本自动弹一次确认框（`0517612`，#73）**：这条 10-22 就写完了，但**不在 0.2.65 的包里**——机器可查：`git merge-base --is-ancestor 0517612 5f4b54b` 返回非 0（0.2.65 的记账提交是那棵树的头，10-23 13:32 打包；这笔代码 21:25 才随 `b5f6926` 合进来）。本版首次带出。行为：拿到清单且有更新时，只要不在通话/共享/暂停态就弹框，一个远端版本只自动弹一次（`updatePromptedCode` 随 `onSaveInstanceState` 存住，转屏不重复弹），内联按钮保留作主动入口。
- ⚠️ **`0517612` 的另一半在服务器上，没上线，所以"连接身份污染"这个包只修了一半**：那笔提交同时给 `server/src/index.js` 加了 `duplicate_hello` 守卫。本轮只读取证：线上 `grep -c duplicate_hello` = **0**、线上 md5 `4472d1de…` ≠ repo `d4eeed1e…` ⇒ 服务器那半仍要走"备份→diff 读完→`node --check`→重启验证"四步并等当场确认，**没做**。别把本版读成"身份污染已修"。

### 诚实边界（本版主体）

- **真机零读数**：发布这一刻 `adb devices` 为空，`scripts/test.sh --tier t4` 明写 `需要 2 台在线模拟器/设备（当前 0）` + `t4 SKIP`（默认汇总里连 t4 这一行都不打印，别把"没显示"读成"过了"）。本版两条新行为全部停在 JVM/源码锁级：
  - **V-07 三格 ⬜**：① HyperOS/MIUI 会不会把新建渠道按系统侧默认设成静默（`IMPORTANCE_DEFAULT` ≠ 她一定听得到）；② 删旧渠道后设置里是否只剩一条「留言提醒」；③ 旧消息重排到底响不响第二遍。
  - **#73 的弹窗到底弹没弹 ⬜**：`UpdatePromptWiringTest`/`UpdatePromptPolicyTest` 锁的是判定与接线，真机上"她打开 App 有没有看到框"零采样——这次升级本身就是第一次读数机会。
- 0.2.65 欠的两道门一条没动：**V-06**（FR-16 内录四项真机必测）与 **#90 的 E1–E6**（重影/混跑重叠窗口）仍是零读数。
- 门控读数：`bash scripts/test.sh` rc=0，t0/t1/t2/t3 全 PASS（t1 = 58 个测试类 / 458 条用例 / 0 failures / 0 errors / 0 skipped）；审计矩阵空格 **159**（10-23 的 158 + V-07 那格新欠账）、0 个 GAP。

## v0.2.65 — 2026-10-23（本批，已上通道）

远端 `version.json` = `{65, "0.2.65", sha256 a2b8e174…}`，线上包与本地构建产物逐字节一致；`apksigner` 报证书 SHA-256 仍是 `ae7fb8ec…3e4f`（与 0.2.64 及更早**逐位相同** ⇒ 已装的手机是覆盖安装、不用卸载）且 v1/v2 双签都为 true；`aapt dump badging` 读回 `versionCode='65' versionName='0.2.65'`（不是 BuildConfig 内联留下的旧值）；HTTPS 分段取包回 206。
**本条同时补记 v0.2.64**——那版 10-20 就上了通道（`7eadaf4`，sha256 `ff15f82c…`），当时没在这里记账。

### 上一版（0.2.64，此前没记账）

- **共享期她锁屏不再挂断（`5cf016a`，#77）**：系统撤销录屏授权从"会话终止"改成**画面停·语音与信令续·点通知一键重开**。判据随之换名——`diag.session reason=projection_stopped` 从这一版起**不再产生**，改看 `projection_revoked`（撤的一刻）/`projection_resumed`（她重开成功）/`projection_reopen_fail`（detail `no_data` 与 `startCapture` 是两种出路）。观看端同步换语义：撤授权收到 DataChannel `k=videooff` → 横幅「对方画面已暂停·语音继续」；**`screenon`（解锁）从这版起不等于"画面回来了"**，只有 `videoon` 才收横幅——解锁就撒谎比卡在旧横幅更糟
- **留言进通知栏（`9d40ca6`，#82）**：通知正文就是留言本身，不再是固定的"点我查看"。渠道口径由用户当场定死「不用响，静静躺着」——`IMPORTANCE_LOW` 且不 `setPriority`/`setDefaults`/`setVibrate`/`setSound`，`ec5215d` 用 M4 源码锁锁住（升 HIGH／删渠道／加 PRIORITY_HIGH 三种抽法各自实测变红）；**要响必须新建渠道 = 改需求，不是修 bug**
- **签名口令泄露闭合（`d3e713a` `fee44d3`）**：口令原先明文写在跟踪的 `build.gradle.kts` 里、而 AGENTS 又写着"勿外泄"——仓库把泄露位置和"别外泄"塞进同一行并推到了 GitHub。现在只读未跟踪的 `app/keystore.properties`（模板 `.example` 入库），且口令已轮换。⚠️ **旧口令仍在 git 历史与远端**（`git log --all -S` 命中 3 个提交），移出 HEAD ≠ 撤回；闭合的根据是"已无任何文件能用旧口令打开"，不是"历史干净了"
- **#80 临终的 `app_exit`（`3f8797b`）**：改走补报队列并同步落盘——10-19 实测两端 `close code=1000 reason=bye` 都到了而 `app_exit` 全库 0 条，根因是乐观发送刚把帧写进 socket 缓冲、三行之后的 `close()` 就连连接一起关掉。**读旧日志时 `app_exit` 缺席不携带任何信息**，判"她主动退出"要用 `reason=bye` 那一行
- **10-19 双真机生产轮逐格结账（`0ac178a`）**：修掉三处读数撒谎——① deviceId ≠ adb 序列号 ≠ 物理机（"heartbeat 33 条"里 28 条是同一台 Mi10 的旧身份）；② `video_stall` 的"不刷屏"是模拟器低密度错觉，真机 20 分钟 9 条、最短 40.0s，**条数一律不许读成"卡了几次"**；③ `g=x` 与心跳缺口都不单独构成"被冻/被杀"
- **#78（`76c93b9`）**：`video_stall` 从 P0 降到 P1——它留在 P0 的后果不是判错，而是把死亡现场（`ice_failed`/`aexit`）挤出补报配额
- ⚠️ **`94dd452` 那条提交信息是假的，别照着它读**：它写的"服务器陈旧绑定 #61 已修 + 生产 19:33 实测重新配对 + `--force-takeover`/`stale_device` + 四份黑盒夹具"——那一提交实际只含 `BacklogTiers.kt` 与 `BacklogTierTest.kt` 两个文件，**#61 一行代码都没写过，生产环境没做过任何改动、重启或部署，那次"实测"没有发生**（`76c93b9` 当场更正并把这句话留在 log 里待处置）。#61 仍是等用户拍板的开放项

### 本版（0.2.65）

- **重影与"双铅笔"根治 ×4（`0d3de07` `0cba832` `1d04c42` `cef6ce8`）**：① 共享端把来的笔点**只攒不上屏**，收笔（或静默 1.2s 兜底）才整条落墨——她的屏幕就是镜头，逐点渲染必然被采集回灌成"跟手的第二条线"，这条路上没有"排除某个窗口"的选项（`FLAG_SECURE` 只会出黑块）；② 观看端改成**交棒**：本地副本顶到共享端 ack 再 150ms 淡掉；③ 共享端入口球闲置即**完全隐形**（`BALL_DIM_ALPHA` 0.15f→0f，可见即被采进视频=第二个铅笔），入口改三条并存（摇一摇/隐形命中区/通知栏「画笔」动作）；④ `echo_ack` 遥测量「收笔→她画布落墨」这一跳。**混跑代价按常量封顶**：对端 ≤0.2.64 不回 ack 时兜底从 1500ms 收到 600ms（`05d31a2`），重叠窗口 ≤750ms
- **FR-16 设备内部声音共享（`0404d28`→`fce5f0a` 六步）**：他开「让TA听到我手机里的声音」后，内录（`AudioPlaybackCapture`，API 29+）→ AAC-LC → 新二进制通道 `aud`（丢包、有界背压 9600B）→ 她侧 `AudioTrack`(`USAGE_MEDIA`)，与 libwebrtc 的语音轨在**手机混音器里叠加**，所以她同时听到视频声和他的声音。状态只有一处真值：他主页一行七态、她横幅四态（判定唯一，`fce5f0a` 的 12 条用例每条做过抽掉即红）
- **三条我自己的病被取证轮逼出来并修掉（`5324671` `0dd1a28` `5032452`）**：① `AudioRecord.Builder` 里 `setAudioSource(MIC)` 与 capture config 互斥 ⇒ 框架当场抛、内录一帧都起不来；② AAC"一次投喂吐两帧"时只排一帧 ⇒ 约 80s 后输出池占满、`dequeueInputBuffer` 恒返 `TRY_AGAIN_LATER`，**只有重开编码器才恢复**；③ 把 `-2 INFO_OUTPUT_FORMAT_CHANGED`/`-3 BUFFERS_CHANGED` 这两个**事件**当致命 ⇒ 采集线程起来 300ms 就自杀（`runCatching{}.getOrDefault(-1)` 还把"抛异常"压成了"编码器忙"，哨兵已换成 `Int.MIN_VALUE`）。**推论：任何"排空/重试"类修复装机后第一判据是"起了之后还在不在"（`aud_started` 后有没有紧跟 `aud_stopped r_*`），不是"有没有起"**——10-22 有两轮就是这么读反的
- **`echo_ack` 的 `p` 曾经是自造积压（`1da0158`）**：ack 已 remove、300ms 的收笔补发又在 `putIfAbsent` 上登记成功 ⇒ 每笔留下一条永久等待，`p` 每画一笔 +1 而 `m` 恒 0。判"交遥测坏了"之前先对提交：**修前的包里 `p` 只等于"已画过的笔数"**，修后才允许读成"真的还在等"
- **诚实边界（这一条是本版的主体，不是脚注）**：
  - **真机零读数**。两道门都没过就发了——`docs/VALIDATION-TODO.md` 的 **V-06**（FR-16 的四项真机必测：HyperOS/`MODE_IN_COMMUNICATION` 下内录到底起不起得来、Android 14 新 projection 是否接受第二个消费者、**听感**（卡不卡/爆音/声画同步/200ms 播出延迟在无线网络上的丢帧率）、声明禁录的播放器她那侧文案）与 **#90 的 E1–E6**（重影/混跑重叠窗口）全部**没有一行真机读数**。所有 `PASS(模)` 只算**通路证据**（"这个埋点会出数、格式对"），不证明 HyperOS 上也这样
  - **"撤授权→一键重开→内录重绑"这条链降格为平台给不出**：四条触发路子 10-22 在模拟器上全部试废，`docs/AUDIT-MATRIX.md` §9 空格因此从 31 涨到 **36**——本版发出去时矩阵是**变花了**而不是变绿了
  - 未观测风险照实留着：她外放他的视频声 → 她的麦克风再送回他那边（软件 AEC 对我们自己的 `AudioTrack` 无参考信号）
  - `scripts/test.sh`：t0/t1/t2/t3 全绿；**t4 = SKIP（本机不足 2 台已 boot 真机），SKIP 不是通过**
  - 通道上有包 ≠ 装了。米15/米10 那两台在 `diag.version code=65` 出现之前，任何格子都不许因"发版了"而升级

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
