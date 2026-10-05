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

## 发版（App 自托管 APK 通道）

```bash
./scripts/publish-release.sh <versionName> <versionCode>   # 如 0.2.23 23
```

流程 = clean → assembleProdRelease → scp 到主机 `guo` → 写远端 version.json（heredoc，防双层引号产坏 JSON）→ 远端 JSON 校验 → 本地 dist 归档（保留最近 2 个）。
发版前：`scripts/test.sh` 全绿 + 同步 bump `app/app/build.gradle.kts` 的 versionCode/versionName。
已安装的 App 下次打开自动检查更新；本机真机可直接 `adb install -r`。

## 服务器（高风险区）

- **生产跑的是 hotfix 漂移常量**：`PAIR_CODE_TTL=30min`、`pair.enter 上限=10`；repo 默认 `10min / 5`。
  单测锁定的是 repo 行为。**禁止 naive 部署**：任何服务器改动上线前必须人工比对线上 env/常量，改动与部署均需用户明确确认。
- 线上：`https://soul.lumi666.cloud`（wss 反代 → :8080）；TURN 3478 + 49160-49200/UDP。
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

## 其他约定

- UI/交互文案面向非技术用户（"她"侧），说人话、给出路（错误必须解释原因+下一步）。
- 改手势/笔迹/授权相关代码后，除非有真机验证，不得声称"手感正常"——JVM 单测只锁逻辑不锁手感。
- 新纯逻辑优先做成可注入时钟/可返回决策的形态再进 Activity/Service，别把判定写死在回调里（参考 docs/TESTING.md §3）。
