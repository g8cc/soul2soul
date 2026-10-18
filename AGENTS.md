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
- 签名口令在 `app/app/build.gradle.kts`（勿外泄、勿写入其他文件）。

## 测试（必读）

唯一入口：`bash scripts/test.sh`（T0 静态 / T1 JVM / T2 server 单测 / T3 协议黑盒 / T4 真机 E2E 门控）。
详见 `docs/TESTING.md`。规则：

- 改纯逻辑 → 必须有对应用例；每条真机踩坑修复 → 必须有锁定用例。
- T1 固定 `:app:testProdDebugUnitTest`（勿跑 flavor×4）。
- 测试**绝不**写 `server/data/pairings.json`（T3 自动用 `S2S_DATA_FILE=$(mktemp)`）。
- 浮点边界断言前用 `node -e "Math.fround(...)"` 复核单精度。

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
- `scripts/e2e.sh` 需 ≥2 台已 boot 设备，探测不到时 test.sh 报 SKIP——**SKIP 不等于通过**，发版说明里要如实写。
- MIUI/HyperOS 坑集中在：FGS 通知折叠（授权要另设对话框入口）、后台电池优化（电池豁免引导）、FLAG_SECURE 采集黑块（禁用）。

### 读服务器日志的口径（v0.2.59 待机遥测起必须知道）

- **`diag.*` 只在已鉴权连接上打印**（`server/src/index.js` 的 `if (!ws.authed) break`）。
  所以**未配对期间的日志必然是空的，这不是埋点坏了**；判"某事件零采样"之前先确认那一刻两端已配对。
- **`detail` 里的 `=` 会被 `safeTag` 磨成 `_`**：补报的真实时刻要 grep `detail=at_`，写 `detail=at=` 是一个不存在的键
  （t0 有守卫盯着 `docs/PROTOCOL.md` 与本仓库脚本别改回去）。
- **一轮复现后先看 `bash scripts/repro-capture.sh summary`**，它按 `docs/PROTOCOL.md` 的
  `DIAG-EVENT-NAMES` 清单逐条列"响了/没响"，不再靠人肉逐条 grep。清单是唯一权威：
  新增事件名不写进那一行，t1 的 `DiagEventRegistryTest` 直接红。
- **`heartbeat` 的缺口本身就是判据**：连续两条 `beat=` 单调读数差 > 2×300s 说明进程被冻或被杀，
  不需要额外事件；设备墙钟不可信（实测 Mi5 慢 ~10.5h），任何"间隔"都不许用 wall-clock 相减。

## 其他约定

- UI/交互文案面向非技术用户（"她"侧），说人话、给出路（错误必须解释原因+下一步）。
- 改手势/笔迹/授权相关代码后，除非有真机验证，不得声称"手感正常"——JVM 单测只锁逻辑不锁手感。
- 新纯逻辑优先做成可注入时钟/可返回决策的形态再进 Activity/Service，别把判定写死在回调里（参考 docs/TESTING.md §3）。
