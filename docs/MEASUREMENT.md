# MEASUREMENT.md — 真机/模拟器测量平台速记

从 `AGENTS.md` 搬出（10-23 瘦身）。**动手取读数之前先读这里**：哪台机器能量什么、量的边界在哪、
以及"这个平台结构上给不出这一格"的判据。写 `⬜` 并附原因是合规的，写"看起来没问题"不是。

## 设备与身份

- 测试机：用户的 Mi5（Android 7，`adb devices` 序列 be2552f6）；对方手机是 Mi15（不在本机，走 App 内更新通道拿新版）。
  ⚠️ **Mi5 驱动不了 UI**：MIUI 8 拒绝 shell 注入，`input tap/swipe/text` 每次都
  `InputDispatcher: Permission denied: injecting event from pid … uid 2000 to window … owned by uid 10012`（只在 logcat 里，**adb 的 rc 仍是 0——别把"没报错"当成"点到了"**，10-20 我在它上面白按十几下才去翻 logcat）。
  那台机器只能**人工点**，或先在开发者选项开「USB 调试（安全设置）」（要小米账号+SIM 才允许开）。
  受影响的格子一律只能留 `⬜`：Android 7 那条通知是否仍是 3 个动作、隐形命中区在真机上点不点得到、真机编码帧率。
  反过来说 `settings put system user_rotation`、`dumpsys`、`exec-out screencap`、`am start` 这些**都不受这条限制**——转屏往返那一格就是靠 `user_rotation` 量出来的。
- **双模拟器这台测量平台有一个明确的时间分辨率上限，别拿它量 150ms 级的东西**：两台模拟器之间的视频段实测 ≈0.5fps（历史 0.2.63 轮的 `qos_tick` 里码率爬到 4.1Mbps、`cap=738x1600` 也还是这个数；当前版本另记录 `cap`/`out`），
  而单帧 `exec-out screencap -p` 是 0.6s（机器一忙、并发两条流就劣化到 1.1~4.4s，10-20 两次都量到过）。
  于是"笔迹在视频里第几帧出现""两条墨有没有同时可见 ≤150ms""混跑时她等成什么样子"这几条**结构上不可分辨**——
  量不到就是量不到，写 `⬜` 并附原因，不许写成"看起来没问题"，也不许用 `screenrecord` 绕（那台机器上它只有 ~1.2fps）。
  能在这台上落的判据只有：像素级"有没有墨"（走廊内粉度增量）、窗口 `mAttrs`、`dumpsys` 动作数、以及 `echo_ack` 这类设备自己报的时刻。
- **日志里的 deviceId ≠ adb 序列号，也 ≠ 物理机**，认领机器前先对这张表（10-18 差点把 Mi10 的真机读数认成米15；10-19 又发现同一台 Mi10 上挂着两个身份）：
  线上生产 `soul.lumi666.cloud` 是**两台机器、三个身份**——
  `106db351-fec1-…`（0.2.59）与 `1a1d64ea-18c4-…`（0.2.63）**同在**本机 Mi10（adb `7c6a9986`，MIUI 13/Android 12），
  `deba9443-74ff-…` = 米15（接不了 adb，版本只能看 `app=`）。
  一台机器多个身份的成因：`deviceId` 存在 App 私有 SharedPreferences（`util/Prefs.kt:12-17`，首启生成 UUID），
  **卸载重装与 MIUI 分身各生成一个**。
  **认领办法（不靠猜，机器可查）**：取任意一条心跳的「服务器行时间 − `k=`」当作该设备内核 boot 的时刻戳，
  同 boot ⇒ 同物理机（10-19 实测两个 Mi10 身份差 0s，并与 `adb shell cat /proc/uptime` 反算一致；米15 差 12.8 天）。
  判"某台手机掉线几次"按物理机聚合，判"某次死是被杀还是被冻"必须按 deviceId 分开。
  两台模拟器连的是本机 dev server（`/tmp/s2s-emu-run/server.log`），它们的读数**不会**出现在生产日志里，
  反过来生产日志里出现的每一条都能落到这两台真机之一。

## 平台上限与操作纪律

- `scripts/e2e.sh` 需 ≥2 台已 boot 设备，探测不到时 test.sh 报 SKIP——**SKIP 不等于通过**，发版说明里要如实写。
- **往用户的手机装包/卸包必须先征得当场同意**（10-19 付过代价）：我为取真机媒体读数，把只改了 `SIGNALING_URL` 的
  emu 包 `adb install -r` 到 Mi10，桌面上出现**两个同名「陪着你」**，用户按名字删掉了真正配过对的正式版。
  两条后果要记住：① `deviceId` 存在 App 自己的 SharedPreferences（`util/Prefs.kt:12-17`，首启生成 UUID），
  **卸载即抹掉**，重装会拿到新号 ⇒ 服务器那条配对从此指向不存在的设备（= #61 陈旧配对死路），只能重新配对；
  ② 生产日志不丢（遥测在服务器侧）。已修：两个源集各有一条独立桌面名——
  `app/app/src/emu/res/values/strings.xml`「陪着你·测试」、`app/app/src/debug/res/values/strings.xml`「陪着你·调试」。
  **实测优先级（10-19 四个变体逐个 `aapt dump badging`，别按名字猜）**：build type 源集压过 flavor 源集，
  所以 `assembleEmuDebug` 落地的图标写的是「陪着你·调试」，「·测试」只在 emu**release** 才出现。
  四格实测：prodRelease=`陪着你`（唯一保留正式名）、prodDebug=`陪着你·调试`、emuDebug=`陪着你·调试`、emuRelease=`陪着你·测试`。
  日常真机联调走 `assembleProdDebug` ⇒ 桌面上看到的是「陪着你·调试」。
  安全不变式是"非 prodRelease 都 ≠ 正式版"，与"哪条名字赢"是两回事，别把后者当成前者的证据。——之所以 debug 也必须有自己的名字：
  它的 applicationId 与线上包**完全相同**（`com.soul2soul.app`）而签名证书不同，
  `adb install -r` 会报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`，**那句报错的标准"解法"是先卸载**，
  deviceId 正是那一刻没的。所以这条路径上"同名"不只是看不出该删哪个，还会诱导人到破坏性解法。
  t0 守卫按源集逐条盯：`src/emu` 与 `src/debug` 的 `app_name` 存在且不等于正式版（三种抽法各自实测变红）。
  口径：**测试包的名字必须自带"可以删"信号**，同名图标 = 用户无法区分 = 迟早删错。
- **本机 dev server 曾借用线上那套 TURN 的凭证**（10-19 用户点头，为的是让模拟器 ICE 走到 CONNECTED，媒体段第一次具备判定力；**10-22 已回收**，现在 `server.log` 打的是 `TURN host = YOUR_SERVER_PUBLIC_IP:3478` + `WARNING: TURN_STATIC_AUTH_SECRET 未设置`）。
  借用期间取到的读数怎么读要一起记住：`gather_done` 会出 `r_1`、`path` 会是 `r-r` 这类真值，**这是"通路证据"不是"真机验证"**（矩阵里记 `PASS(模)`），
  它不代表米15/米10 的选路也这样；而反过来拿它当"TURN 坏了"的证据更是误读。**规程**：用完必须把 `TURN_HOST/TURN_PORT/TURN_STATIC_AUTH_SECRET`
  改回占位值，否则以后每次 t4 都在偷偷依赖生产 coturn；凭证值不写进任何文件（ssh 命令替换注入环境变量）。
- MIUI/HyperOS 坑集中在：FGS 通知折叠（授权要另设对话框入口）、后台电池优化（电池豁免引导）、FLAG_SECURE 采集黑块（禁用）。

## 模拟器驱动 UI 的读数口径（10-22 FR-16 取证轮踩的两条）

- **`scripts/ui.py tap` 不带 `once` 时是"点完确认目标消失、没消失就再点"的循环**，这对两类控件是两种坏法：
  ① **可逆控件**（开关、复选）——每一轮重试都把状态拨回去，一条"开→采→关"的脚本能滚成开/关来回七八次，
  取证脚本看到的 `aud_started/aud_stopped` 增量就不再对应任何一次真实点击（10-22 那一轮就是这么卡住的）；
  ② **有时限的页面**（呼叫页 15s 无人接自动挂）——一轮 snapshot+tap+verify 是好几秒，等到第 3~4 次重试窗口已经关了，
  现象是 A 侧留下 `invite sent` 而 B 早已回到主页、`signal: accept` 零条，**这不是配对坏了，是脚本太慢**。
  所以：拨开关一律 `once`；接呼叫除了 `once` 还要**先把被叫端顶到前台**再让对方发起。
- **MediaCodec 的负数返回值里只有一种是故障**：`dequeueOutputBuffer` 会返回
  `-1 INFO_TRY_AGAIN_LATER`（这一拍没有）、`-2 INFO_OUTPUT_FORMAT_CHANGED` 与 `-3 INFO_OUTPUT_BUFFERS_CHANGED`（**事件**，
  编码器起流必报一次 format changed，之后每次格式/缓冲集合变动再报）。把 -2/-3 当致命 ⇒ 采集线程起来 300ms 就 self-kill。
  另一条同族的坑：`runCatching { codec.… }.getOrDefault(-1)` 把**抛异常**压成 `-1`，而 `-1` 恰好等于 `TRY_AGAIN_LATER`
  ⇒ 真坏与只是没帧在日志里同一个形状。哨兵必须用一个不可能撞上任何 codec 返回值的数（本仓库用 `Int.MIN_VALUE`）。
  **推论（不只适用于 codec）**：任何"排空/重试"类修复装机之后，第一判据是"起了之后还在不在"（`aud_started` 之后有没有紧跟一条
  `aud_stopped r_*`），不是"有没有起"。10-22 有两轮读数是这么来的：只看 `aud_started` 出条就判通过，结果上一轮的修复本身就是下一轮的故障。
