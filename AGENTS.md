# AGENTS.md — 本仓库工程约定（供人与 AI Agent 共用）

本文件只放**每次都要守的规则**。读数口径、平台上限、日志字段这类"用到才需要"的知识已外分（10-23 瘦身：本文件从 266 行降到约 100 行，搬走的条目原文落在下面那张表里，没有删内容）。

## 文档地图

| 什么时候必须读 | 文件 |
|---|---|
| 写/改测试、加锁定用例、查坑㉞编号台账 | `docs/TESTING.md` |
| 动任何埋点，或要从 `diag.*` 日志下结论 | **`docs/DIAG-READINGS.md`** |
| 取真机/模拟器读数（设备身份表、平台上限、`ui.py` 纪律、装包后果） | **`docs/MEASUREMENT.md`** |
| 动 keystore / 签名口令 / 发版签名验收 | **`docs/SIGNING.md`** |
| 线上协议与事件名（唯一权威清单） | `docs/PROTOCOL.md` |
| 审计交付（状态×事件矩阵，`⬜`=欠债） | `docs/AUDIT-MATRIX.md` + `docs/AUDIT.md` |
| 真机还欠哪些场景（V-01…V-06） | `docs/VALIDATION-TODO.md` |
| 需求与验证标准 | `docs/PRD.md`（`docs/SPEC.md` §3 是过时设计稿，协议以 PROTOCOL 为准） |
| 发版历史与"这版到底验过没有" | `CHANGELOG.md` |

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
- **签名口令不入库**：`app/app/build.gradle.kts` 只读未跟踪的 `app/keystore.properties`（模板 `.example` 入库）。
  PKCS12 只有一个口令、`keytool` 那个 stub 的退出码怎么读、发版签名的三条复测读数 → `docs/SIGNING.md`。

## 测试（必读）

唯一入口：`bash scripts/test.sh`（T0 静态 / T1 JVM / T2 server 单测 / T3 协议黑盒 / T4 真机 E2E 门控）。
详见 `docs/TESTING.md`。规则：

- 改纯逻辑 → 必须有对应用例；每条真机踩坑修复 → 必须有锁定用例，且**"抽掉即红"要实测过**（把修复回滚，用例必须变红，否则不算锁定）。
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
- **状态只有一处真值**：同一判定被两处各自推断，迟早对不上（内录状态、球的位置、暂停/恢复都按这条收口）。
- 新有状态功能上线前矩阵先建表头，格子可以空着，表不能没有。

## 发版（App 自托管 APK 通道）

```bash
./scripts/publish-release.sh <versionName> <versionCode>   # 如 0.2.65 65
```

流程 = clean → assembleProdRelease → scp 到主机 `guo` → 写远端 version.json（heredoc，防双层引号产坏 JSON）→ 远端 JSON 校验 → 本地 dist 归档（保留最近 2 个）。
发版前：`scripts/test.sh` 全绿 + 同步 bump `app/app/build.gradle.kts` 的 versionCode/versionName + 签名复测（`docs/SIGNING.md`）。
已安装的 App 下次打开自动检查更新；本机真机可直接 `adb install -r`。
**发版说明必须照实写验证状态**：真机零读数的格子就写零读数、`PASS(模)` 只算通路证据——执行版就是 `CHANGELOG.md` 里的「诚实边界」段。

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
- 协议以 `docs/PROTOCOL.md` 为准。

## Git 规则

- **绝不 `git push`，除非用户当场明确要求**。本地提交可（每个提取/修复独立提交），推送不是。
- 提交信息用中文一句话说明"改了什么 + 为什么"，回归类写明锁定的版本。
- 不做破坏性操作（reset --hard / 删分支 / 强推）除非用户明确要求并确认。
- **提交信息里不许有没发生过的实验**：没写过的代码不能写"已修"，没跑过的不能写"实测"。本仓库有过一次（`94dd452`，由 `76c93b9` 当场更正并记进 CHANGELOG），代价是 log 与发版说明长期撒谎、git 锚点不再可信。

## 过度声称：四条一直生效的红线

- **`PASS(模)` 只是通路证据，不是真机验证**——它证明"这个埋点会出数、格式对"，不证明 HyperOS/MIUI 上也这样。
  强制手段（`cmd deviceidle enter`、`am set-standby-bucket`、`svc wifi/data disable`）造出来的是**可达性测试**，ROM 的自然行为仍属未采样。
- **`SKIP` 不等于通过**（t4 探测不到 ≥2 台已 boot 设备就是 SKIP），发版说明里要如实写。
- **量不到就写 `⬜` 并附原因**，不许写"看起来没问题"。双模拟器那台结构上分辨不了 150ms 级的东西（量程与判据见 `docs/MEASUREMENT.md`）。
- **改手势/笔迹/授权相关代码后，除非有真机验证，不得声称"手感正常"**——JVM 单测只锁逻辑不锁手感。

## 操作纪律（会留下后果、需要当场点头的动作）

- **往用户的手机装包/卸包必须先征得当场同意**：卸载会抹掉 `deviceId`（App 私有 SharedPreferences，首启生成 UUID）⇒ 服务器那条配对从此指向不存在的设备，只能重新配对。
  桌面名已按源集区分（prodRelease 是唯一正式名，日常联调落地的图标是「陪着你·调试」）；来龙去脉与 t0 守卫见 `docs/MEASUREMENT.md`。
- **本机 dev server 若借用线上 TURN 凭证，用完必须改回占位值**（否则以后每次 t4 都在偷偷依赖生产 coturn）；
  凭证值不写进任何文件（ssh 命令替换注入环境变量）。10-22 已回收一次，现状判据见 `docs/MEASUREMENT.md`。
- 生产环境的部署/重启属于同一类：先说要动什么、为什么要动，等当场确认。

## 读日志的两条最易踩禁区（其余全部在 docs/DIAG-READINGS.md）

- **设备墙钟不可信**（实测 Mi5 慢 ~10.5h）：任何"间隔"都不许用 wall-clock 相减；单调锚点用 `k=`（elapsedRealtime 秒）。
- **`diag.*` 只在已鉴权连接上打印**（`server/src/index.js` 的 `if (!ws.authed) break`）：未配对期间日志必然是空的，这不是埋点坏了；判"某事件零采样"前先确认那一刻两端已配对。

## 其他约定

- UI/交互文案面向非技术用户（"她"侧），说人话、给出路（错误必须解释原因+下一步）。
- 新纯逻辑优先做成可注入时钟/可返回决策的形态再进 Activity/Service，别把判定写死在回调里（参考 docs/TESTING.md §3）。
