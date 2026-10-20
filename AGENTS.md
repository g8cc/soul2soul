# AGENTS.md — 本仓库工程约定（供人与 AI Agent 共用）

## 项目一句话

Soul2Soul（"陪着你"）：情侣双人屏幕共享 + 涂鸦 + 语音 + 远程操控。
Android Kotlin 客户端在 `app/`，Node.js WebSocket 信令服务器在 `server/`，WebRTC 媒体直连（TURN 经服务器签发时限凭证）。

## 构建

```bash
# Android（app/ 没有 gradlew 包装器，必须用系统 Gradle + JDK17）
export JAVA_HOME=/Users/wardonguo/tools/jdk17/jdk-17.0.2.jdk/Contents/Home
~/tools/gradle-8.7/bin/gradle -p app assembleProdDebug        # 日常调试
~/tools/gradle-8.7/bin/gradle -p app assembleProdRelease      # 发版产物
```

- Flavor：`prod`（连云服务器）/ `emu`（applicationId 加 `.emu`，连 `ws://10.0.2.2:8080` 双模拟器）× debug/release 共 4 变体。
- **不要用 `-P` 属性切换信令 URL**（BuildConfig 任务对属性变化不敏感，会拿旧值）。
- 版本 bump 必须配合 `clean`：Kotlin 会内联 BuildConfig 常量（v0.2.12 实锤 hasUpdate 焊死）。`publish-release.sh` 已内置 clean。
- **签名口令不入库**：`app/app/build.gradle.kts` 只读 `app/keystore.properties`（`.gitignore` 覆盖；模板 `app/keystore.properties.example`）。缺该文件时 release 不配置签名，
  `packageProdRelease` 直接报 `SigningConfig "release" is missing required property "storeFile"`（10-19 实测 rc=1，不会悄悄产出未签名包）。
  **泄露已于 10-19 闭合：口令轮换过（`keytool -storepasswd`）**，旧值只剩在 git 历史里（`git log --all -S` 那 3 个提交）且已无任何文件能用它打开（实测 `keytool -list` rc=1 `password was incorrect`）。
  换的时候三个必须知道的点：① **`app/keystore/*.jks` 实际是 PKCS12**（`file` 报 data、头 4 字节是 DER SEQUENCE `30 82 …` 而不是 JKS 魔数 `feedfeed`；扩展名骗人），
  PKCS12 **只有一个口令** ⇒ `keytool -keypasswd` 直接 `UnsupportedOperationException`，正确做法是 `storePassword`/`keyPassword` 写成轮换后的**同一个值**；
  ② `keytool` 是系统 stub，必须先 `export JAVA_HOME=/Users/wardonguo/tools/jdk17/…`，否则报 `Unable to locate a Java Runtime`——它**确实返回 rc=1**（实测），失败信号一直在，是我用 `keytool … | grep` 加 `${PIPESTATUS[0]}` 把它丢了（`PIPESTATUS` 是 bash 专有，Agent 侧 `/bin/zsh` 里展开为空，于是链尾的写文件照样执行）：**结果是"keystore 仍旧口令、配置文件已改成新口令"的半执行状态**，口令没换而构建配置坏了，见 `docs/TESTING.md` ㉞；取退出码要么不接管道、要么 `$?`；
  ③ 验收 = 证书指纹逐位不变（`keytool -printcert -jarfile` 与 `apksigner verify --print-certs` 都是 `AE:7F…3E:4F`）+ `apksigner` 报 v1/v2 双签为 true ⇒ 已装手机仍能覆盖安装。改口令前的 `.jks` 备份在 `app/keystore/soul2soul-release.jks.bak-20261019`（`keystore/` 整个被忽略）。
  t0 两条守卫盯着：构建脚本里出现 `storePassword = "…"` 字面量即红、`.gitignore` 少了 `keystore.properties` 那行即红。

## 测试（必读）

唯一入口：`bash scripts/test.sh`（T0 静态 / T1 JVM / T2 server 单测 / T3 协议黑盒 / T4 真机 E2E 门控）。
详见 `docs/TESTING.md`。规则：

- 改纯逻辑 → 必须有对应用例；每条真机踩坑修复 → 必须有锁定用例。
- T1 固定 `:app:testProdDebugUnitTest`（勿跑 flavor×4）。
- **脚本在跑的时候不要编辑它**：bash 是按字节偏移续读脚本文件的，运行中改 `scripts/e2e.sh`（哪怕只改注释、只要长度变了）
  会让正在跑的那一轮从错误的偏移开始解析——现象是"莫名语法错/少跑几段"，不是"我改的内容有问题"。
  要改就先停掉这一轮再重启（10-19 复跑 t4 时踩过一次，那轮直接作废重跑）。
- 测试**绝不**写 `server/data/pairings.json`（T3 自动用 `S2S_DATA_FILE=$(mktemp)`）。
- 浮点边界断言前用 `node -e "Math.fround(...)"` 复核单精度。
- **否定式判据（`.none {}` / `! grep -q`）的扫描范围必须是完整函数或完整文件，不许用魔法行数窗口**（10-19 一轮扫出四条同族空锁，详见 `docs/TESTING.md` 坑㉚）：窗口比被禁的那段短，禁令就只贴在开头几行，实测能放着"锁屏 15 秒自动结束"那种已废代码写回去而用例照绿。取段用 `functionBody`（按声明缩进找收尾 `}`）/ `between`（下一个兄弟 case 标签）/ 整文件。
  同一族还有两个方向要防：配对判据要**按用例方法**作用域（按文件同名 `val` 会误伤成恒红），扫描器找不到锚点时兜底必须是 `?: error(...)` 而不是 `?: (start + 30)`（数字兜底＝静默变短）。这两条已由 t0 守卫盯着。

## 审计（必读，docs/AUDIT.md）

v0.2.44 悬浮球丢失后立的规矩——多轮"稳定性审计"仍漏了一看就该发现的丢球 bug，根因是审计覆盖不可核查。约束：

- **审计交付物是矩阵不是报告**：有状态的功能必须在 `docs/AUDIT-MATRIX.md` 有"状态×事件"表；
  每轮审计先补 `⬜` 空格再复核格子；空格=欠债，t0 每次打印数量。
- **修复=登记机器可查不变式**：真机踩坑必须提炼成 gate 进 t0 静态守卫（grep 类）或 t1 源码扫描
  测试（结构配对类，参考 OverlayBallWiringTest）；把修复回滚用例必须变红，否则不算锁定。
- **每轮过全 5 维度**：恢复对称性 / 生命周期与转屏 / 权限中途收回 / 多入口多出口配对 / 降级老机型。
  只盯"崩溃与数据"的镜头会放过"不崩、无日志、UI 状态丢失"这类 bug。
- **hideX 必穷举 restoreX**：任何隐藏/拆窗/淡化操作，列出全部触发路径逐一确认有恢复；
  成对操作收口为单一方法（如 finishDoodle=拆层+还球），不给调用方漏配的机会。
- 新有状态功能上线前矩阵先建表头，格子可以空着，表不能没有。

## 发版（App 自托管 APK 通道）

```bash
./scripts/publish-release.sh <versionName> <versionCode>   # 如 0.2.23 23
```

流程 = clean → assembleProdRelease → scp 到主机 `guo` → 写远端 version.json（heredoc，防双层引号产坏 JSON）→ 远端 JSON 校验 → 本地 dist 归档（保留最近 2 个）。
发版前：`scripts/test.sh` 全绿 + 同步 bump `app/app/build.gradle.kts` 的 versionCode/versionName。
已安装的 App 下次打开自动检查更新；本机真机可直接 `adb install -r`。

## 服务器（开发环境，10-18 用户重新定性）

- **定性**：用户 10-18 明确「我们没有生产服务，云服务器是开发环境，一切为了修复 bug 为优先级」。
  所以服务器改动**不再需要逐次上线审批**——但"naive"仍然禁止，流程改成机械四步，缺一步就不许重启：
  ① 备份在位（`src/index.js.bak-YYYYMMDD-HHMMSS`）；② `diff` 线上 vs repo 并**读完**行为差，
  重点核对 env 注入的常量（线上 `.env` 给的是 `PAIR_CODE_TTL_MS=1800000`/`PAIR_ENTER_MAX_ATTEMPTS=10`，
  repo 默认 `10min/5`，**必须确认这些值来自 env 而不是被 repo 默认值顶掉**）；③ 远端 `node --check` 每个改动文件；
  ④ 重启后验证（新日志格式、`wss://` 握手回 `registered`、明文凭证 0 条、在场设备重连）。
  依赖文件必须同批上传（例：repo `index.js` 往 `PairCodeBook` 注入 `isPaired`，只传 index.js 会让 `already_paired` 判定静默失效）。
- 线上：`https://soul.lumi666.cloud`（wss 反代 → :8080）；TURN 见 `.env`（当前 `TURN_HOST=49.232.173.115`、`TURN_PORT=3479`；本条曾写 3478，以 `.env` 为准）+ 49160-49200/UDP。
- 重启会踢掉在场设备的信令连接（客户端 3s 内自动重连），**并且会踢掉采集里的 adb logcat 传输**——`adb install -r` 与服务器重启之后都要重跑 `repro-capture.sh stop && start`，否则 `check` 会报 `FAIL device logcat 进程已退出`。
- 配对数据 `server/data/pairings.json` 原子写（tmp+rename），线上文件是活数据，测试不得触碰。
- 协议以 `docs/PROTOCOL.md` 为准（`docs/SPEC.md` §3 为过时设计稿，已加勘误指针）。

## Git 规则

- **绝不 `git push`，除非用户当场明确要求**。本地提交可（每个提取/修复独立提交），推送不是。
- 提交信息用中文一句话说明"改了什么 + 为什么"，回归类写明锁定的版本。
- 不做破坏性操作（reset --hard / 删分支 / 强推）除非用户明确要求并确认。

## 真机联调速记

- 测试机：用户的 Mi5（Android 7，`adb devices` 序列 be2552f6）；对方手机是 Mi15（不在本机，走 App 内更新通道拿新版）。
- **日志里的 deviceId ≠ adb 序列号，也 ≠ 物理机**，认领机器前先对这张表（10-18 差点把 Mi10 的真机读数认成米15；10-19 又发现同一台 Mi10 上挂着两个身份）：
  线上生产 `soul.lumi666.cloud` 是**两台机器、三个身份**——
  `106db351-fec1-…`（0.2.59）与 `1a1d64ea-18c4-…`（0.2.63）**同在**本机 Mi10（adb `7c6a9986`，HyperOS/Android 12），
  `deba9443-74ff-…` = 米15（接不了 adb，版本只能看 `app=`）。
  一台机器多个身份的成因：`deviceId` 存在 App 私有 SharedPreferences（`util/Prefs.kt:12-17`，首启生成 UUID），
  **卸载重装与 HyperOS 分身各生成一个**。
  **认领办法（不靠猜，机器可查）**：取任意一条心跳的「服务器行时间 − `k=`」当作该设备内核 boot 的时刻戳，
  同 boot ⇒ 同物理机（10-19 实测两个 Mi10 身份差 0s，并与 `adb shell cat /proc/uptime` 反算一致；米15 差 12.8 天）。
  判"某台手机掉线几次"按物理机聚合，判"某次死是被杀还是被冻"必须按 deviceId 分开。
  两台模拟器连的是本机 dev server（`/tmp/s2s-emu-run/server.log`），它们的读数**不会**出现在生产日志里，
  反过来生产日志里出现的每一条都能落到这两台真机之一。
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
- **本机 dev server 目前借用了线上那套 TURN 的凭证**（10-19 用户点头，为的是让模拟器 ICE 走到 CONNECTED，媒体段第一次具备判定力）。
  后果要一起记住：`gather_done` 会出 `r_1`、`path` 会是 `r-r` 这类真值，**这是"通路证据"不是"真机验证"**（矩阵里记 `PASS(模)`），
  它不代表米15/米10 的选路也这样；而反过来拿它当"TURN 坏了"的证据更是误读。媒体跑完后要把 `TURN_HOST/TURN_PORT/TURN_STATIC_AUTH_SECRET`
  改回占位值，否则以后每次 t4 都在偷偷依赖生产 coturn。凭证值不写进任何文件（ssh 命令替换注入环境变量）。
- MIUI/HyperOS 坑集中在：FGS 通知折叠（授权要另设对话框入口）、后台电池优化（电池豁免引导）、FLAG_SECURE 采集黑块（禁用）。

### 读服务器日志的口径（v0.2.59 待机遥测起必须知道）

- **`diag.session reason` 的版本分界是 0.2.61**（10-19 抓到四处"同一个字符串掩盖两种死法"，全部已在 repo 修掉）：
  ≤0.2.60 里 `local_stop` 同时涵盖"她按了停止"与"HyperOS 撤销录屏授权"（`MediaProjection.Callback.onStop()`
  不占 first-wins 槽，被收尾那条 `local_stop` 覆盖），共享端被对方挂断则只剩 `local_close`。
  0.2.61 起可直接读：`projection_stopped`＝系统撤的授权、`remote_bye`＝她挂的、`peer_gone`＝只是信令掉线
  （媒体可能本来就好）、`call_canceled`＝主叫在授权阶段反悔（`setupMs=-1`，从没连过，别读成"连上几秒被挂断"），
  另有 `diag.event stop_tap detail=src_main_page|src_notify` 回答"她点的是哪一个停止入口"。
  现网补救读法（≤0.2.60 唯一可用的一列）＝同一行 `proj`：`stopped`⇒被撤销、`live`⇒她按的；`proj=_` 的会话永久无法判。
  **0.2.64 起 `reason=projection_stopped` 不再产生**（这一版把"撤授权"从会话终止改成画面停·语音续）：
  ≥0.2.64 的判据换成 `diag.event projection_revoked`（撤的一刻）/ `projection_resumed`（她重开成功）/
  `projection_reopen_fail`（重开失败，detail `no_data` 与 `startCapture` 是两种出路）。
  ⚠️ 两个方向都会读反：在新版本日志找 `projection_stopped` 判"埋点坏了"是错的，它只是不再该出现；
  把 `proj=stopped` 读成"她挂了"也是错的，`proj` 只回答收尾那一刻的采集状态，不回答中途有没有被撤过。
- **`reason=projection_stopped` 到 0.2.63 为止，0.2.64 起它根本不再产生**：系统撤销录屏授权（她锁屏省电、或在系统隐私提示里点"停止录制"）
  从这一版起**不是会话终止**，而是"画面停·语音与信令续·一键重开"。判据换了一条：
  `diag.event projection_revoked`（detail `keepalive`）＝画面被撤而会话还活着；`projection_resumed`＝她重开成功；
  `projection_reopen_fail` 的 detail 分两种出路（`no_data`=通知没把 Intent 带回来，`startCapture`=新 token 起不来采集，只能再授权一次）。
  两个方向都不许读反：拿 ≥0.2.64 的日志找 `projection_stopped` 会得出"这版没埋"，而它只是不再该出现了；
  反过来把 `proj=stopped` 当成"她挂了"也是错的——`proj` 只描述收尾那一刻的采集状态，不回答"中途有没有被撤过"。
  观看端侧同步换了语义：撤授权收到的是 DataChannel `k=videooff`（横幅「对方画面已暂停·语音继续」+ 暂停 `video_stall` 判定），
  **`screenon`（解锁）从这一版起不等于"画面回来了"**，只有 `videoon` 能收横幅——解锁就撒谎比卡在旧横幅更糟。
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
  所以"40s 一条"是静止画面的正常密度，绝不许把 `video_stall` 的**数量**读成"卡了几次"（#78，它是 P0、会吃补报配额）。
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
- **`app_exit why=destroy` 自 commit 3f8797b 起只以补报行出现（`detail=at_…_why_destroy`）——但这一条尚未随任何已发布版本上线**（现网 0.2.63 仍是旧行为，在那台版本上"0 条 app_exit"是预期而不是修复失效）：旧版走乐观发送而 `onDestroy` 三行之后就 `close()`，帧还躺在 socket 缓冲里就被关掉——10-19 的铁证是 17:48:10 两端同时留下 `code=1000 reason=bye` 而 `app_exit` 全天 0 条。改进入队后代价是"到达很晚（她下次打开 App 才到）或永不到（再也不打开）"，所以**读旧日志时 `app_exit` 缺席不携带任何信息**，判"她主动退出"要用 `code=1000 reason=bye` 那一行。
- **`video_stall` 的条数不是卡死次数**：真机静止画面下最短 **40s** 就能再凑一条（通知/时钟秒针让帧回来一次，streak 清零后 30s 又能凑满 3 个窗口），10-19 一通 20 分钟里 9 条。它过去算 P0，一通 20 分钟能吃掉 9 格补报配额（#78 起降到 P1，与心跳合用 25 格；判"这条能不能丢"的口径是重复那几条是否携带新信息，不是它在不在这份例外清单里）。判卡死必须成对看共享端同刻 `qos_tick` 第 7 格，单看这一条一律不判。
- **`echo_ack` 量的只是"收笔 → 她画布落墨"这一跳，不含视频管线（0.2.65 起）**：ack 与笔迹走**同一条 anno 通道**（要测的就是这条路的排队+往返；走可靠 ctl 会测到另一条队列的队头阻塞，得出"很快"的假读数），
  所以 `e=<ms>` 是观看端**交棒时刻的下界校验**（ack 都到不了，视频更到不了），**不是**"回显总延迟"——采集/编码/网络/解码/渲染那几段一概不在里面。
  `detail=e_,x_,m_,p_` 里 `m`（miss）与 `p`（pending）**大于 0 是正常**（anno 是丢包通道，兜底 1500ms 会接手），不许读成"功能坏了"；`echo_ack` 这个事件在补报队列里是 **P1**（重复采样，单条丢掉不丢结论，p50/p90 由整序列给出）。
  两端版本必须一起看：**0.2.64 及以前的对端既不回 ack 也不认 `k="a"`**（`SignalRouter` 的 `else -> None`），那一版 `echo_ack` 整段缺席是**版本差**，不是埋点坏了。
  同一版还有两个读数口子要知道：共享端入口球 `BALL_DIM_ALPHA` 从 0.15f 改成 **0f**（可见即被采进视频=第二个铅笔），所以"她看不到画笔入口"不再等于"入口坏了"，三条入口（摇一摇/隐形命中区/通知动作）里只有第一条在 HyperOS 折叠通知下稳定。

## 其他约定

- UI/交互文案面向非技术用户（"她"侧），说人话、给出路（错误必须解释原因+下一步）。
- 改手势/笔迹/授权相关代码后，除非有真机验证，不得声称"手感正常"——JVM 单测只锁逻辑不锁手感。
- 新纯逻辑优先做成可注入时钟/可返回决策的形态再进 Activity/Service，别把判定写死在回调里（参考 docs/TESTING.md §3）。
