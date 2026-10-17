# Soul2Soul 测试体系（TESTING）

## 1. 分层定义

唯一入口：`scripts/test.sh`（分层 harness）。

| 层 | 内容 | 命令内部 | 依赖 | 默认执行 |
|---|---|---|---|---|
| T0 | 静态检查 | `bash -n scripts/*.sh`；`node --check` 全部服务端 JS；AndroidManifest XML 良构（python minidom）；package.json JSON 校验 | 无 | ✅ |
| T1 | Android JVM 单测 | `gradle -p app :app:testProdDebugUnitTest` | JDK17 + Gradle 8.7 | ✅ |
| T2 | Server 单测 | `node --test 'test/*.test.mjs'`（pairing/turn/messages 纯逻辑） | node ≥ 22 | ✅ |
| T3 | 协议黑盒 | 随机空闲端口起临时服务器（`S2S_DATA_FILE=$(mktemp)`）→ 等 `/health` → 协议/冒烟/留言/召回/存量回填黑盒 → trap 清理 | node | ✅ |
| T4 | 模拟器/真机 E2E | 复用 `scripts/e2e.sh`，需 adb + ≥2 台已 boot 设备 | adb、设备 | ❌ 门控 |

关键保证：**测试永不写 `server/data/pairings.json`**（T3 用临时数据文件），跑完无残留 node 进程。

## 2. 用法

```bash
bash scripts/test.sh              # T0–T3，秒级到分钟级，日常默认
bash scripts/test.sh --tier t2    # 只跑某一层：t0|t1|t2|t3|t4
bash scripts/test.sh --coverage   # 追加覆盖率（见 §5）
bash scripts/test.sh --e2e        # 探测到 adb + 2 台 boot 设备才执行 T4，否则 SKIP
bash scripts/test.sh --strict-audit # 未填矩阵格子(⬜)也让 T0 FAIL，用于发版前审计
```

参数和退出码是门禁的一部分：`--tier` 只接受 `t0`、`t1`、`t2`、`t3`、`t4`；显式指定
`--tier t4` 会自动启用 T4。各层函数返回 `0=PASS`、`1=FAIL`、`2=SKIP`，汇总中的
`SKIP` 不会伪装成 `PASS`；日常模式允许 T4 因没有双设备而 SKIP，发版前应人工确认。
T0 始终拦截矩阵中的 `GAP`；`--strict-audit` 额外拦截未填的 `⬜`。

T4/E2E 不再依赖某台开发机的绝对路径，可用以下变量接入 CI 或其他电脑：

```bash
ADB_BIN=/path/to/adb EMU_BIN=/path/to/emulator \
APK_PATH=/tmp/s2s-emu.apk E2E_S1=emulator-5554 E2E_S2=emulator-5556 \
bash scripts/test.sh --e2e
```

失败时 `scripts/e2e.sh` 返回非零并保留日志/截图目录；只有本机由脚本启动的模拟器会在
`KEEP_OFF=1` 时被清理，不会误杀用户已经启动的设备。`scripts/drive-test.sh` 也遵循同一
套环境变量和 `PASS/FAIL` 退出语义。

环境事实（别踩坑）：
- `app/` **没有 gradlew 包装器**，用系统 Gradle：
  `JAVA_HOME=/Users/wardonguo/tools/jdk17/jdk-17.0.2.jdk/Contents/Home ~/tools/gradle-8.7/bin/gradle -p app <task>`
- 固定跑 `testProdDebugUnitTest`（勿用 `testDebugUnitTest`——flavor 矩阵会把同一套用例跑 4 遍）。
- node v24 的 `node --test` **不接受目录参数**（`Cannot find module`），必须用带引号的 glob `'test/*.test.mjs'`。
- T4 双模拟器（`scripts/e2e.sh` + `scripts/drive-test.sh`）十六个必踩坑，脚本已内置处理：
  ① swiftshader 无头模式里 systemui 常弹 ANR，会卡死 `uiautomator dump`，表现为"按钮永远点不到"（实测烧光配对 4 次重试）→ `settings put global hide_error_dialogs 1`；
  ② `pm clear` 后首次启动会弹 v0.2.51 的电池豁免引导，压在主页之上，必须先点「取消」才找得到「显示我的配对码」；
  ③ 自动息屏会在无关阶段制造锁屏事件、污染 AC-6 判据 → `settings put system screen_off_timeout` 拉长；
  ④ **上一轮 Ctrl-C 会留下 systemui 的投屏授权窗**：`uiautomator` 只 dump 顶层窗口，于是「一起玩」明明在界面上却查不到（实测配对已成功仍被判失败）→ 开局 `am force-stop` + `KEYCODE_HOME` 把界面归零。同一类的还有**灭屏残留**：drive-test.sh 的 AC-6 会真按电源键，脚本一崩（见坑⑯）屏幕就黑着留给下一轮，实测下一轮第一步「A 生成配对码」直接读空 → 开局一律 `KEYCODE_WAKEUP` + `wm dismiss-keyguard`，收尾 cleanup 也幂等地再做一次（灭屏不是"等等就好"的可恢复状态）。
  ⑤ 一次 dump 实测 ~4s（宿主 load 30+ 时 10s+），它就是天然的轮询间隔——再叠 `sleep 3` 会让一轮重试耗时翻倍（原来的 8×sleep 版光授权轮询就吃 70 秒）；
  ⑥ **来电页只有 30 秒**（`SessionActivity:946 INCOMING_TIMEOUT_MS`，到点自动 `decline`）。接听阶段前每一次 dump 都在吃这个窗口：实测 dump 轮询「B 收到来电」+ 逐次校验把 30s 烧光，主叫只收到 `signal: declined`。所以来电与接听一律用 logcat 判据（见铁律②修订）；
  ⑦ **`--ez autoAccept` 兜底钩子是死的**：`SessionActivity` 未 exported，adb（uid 2000）起不动，实测 `Permission Denial: not exported from uid 10202`；`run-as` 也不行（`am` 会以 com.android.shell 身份被 assertPackageMatchesCallingUid 拒掉）。别再把它当"点不到就兜底"的保险；
  ⑧ **"授权弹窗出现"不等于"已允许"**：投屏是两步弹窗（先选「整个屏幕」再点 Start now），实测点一次 Start now 后 systemui 仍占着焦点、授权根本没落地，于是 `invite` 永远不发。所以 e2e.sh 把两条拆成独立断言（出现弹窗 / invite 已发出），后者用 logcat 重试轮询作真判据，红了就带弹窗文本落盘并直接退出。
  ⑨ **`ui tap` 的词必须落在 clickable 节点上**：`uiautomator` 按文档顺序返回节点，而说明文字永远排在按钮前面——`接受` 先命中「TA 想和你一起玩手机，接受吗？」的正文，`Start recording` 先命中「Start recording or casting…」的标题。实测正文被当成按钮连点几十秒：来电 30s 到点自动 `decline`，投屏授权永不落地。`scripts/ui.py` 现在优先取 `clickable="true"` 的命中，校验也按同一口径（可点目标消失=可点节点没了）；点完仍在界面上的目标（单选钮）传第 4 个参数 `once` 只点不校验，由业务判据（`invite sent`）证明生效。
  ⑩ **ICE 在双模拟器上是结构性不可过，不是产品 bug**：每个模拟器的 qemu SLIRP 都把自己报成 `10.0.2.15`，host candidate 互相指着自己，guest↔guest 本就不通；本地 dev server 不设 `TURN_HOST` 时签发的是占位符 `YOUR_SERVER_PUBLIC_IP`（`server/src/index.js:22`），于是 srflx/relay 也都没有。实测 `signal: ice` 正常收发（A 收 2 条、B 收 6 条）但 `ice state: CHECKING->FAILED`(+15s)、零 `viewer-ready`。所以媒体段（同屏/笔迹）先判 ICE：连上了就硬断言；只有「FAILED>0 且零帧」这种环境特征才记 `SKIP(环境)` 并让整层返回 2，其它形态（gathering 卡死、进程被杀、握手没发）一律算真失败。
  ⑪ **会话退出的判据不能用主页文案**：挂断/对端离开之后，v0.2.51 的电池豁免引导会**再次**弹出盖住主页，实测 `mCurrentFocus` 已是 `MainActivity` 而 dump 里只有「让它随叫随到 / 确定」→ 旧断言「挂断后 B 回到主界面」恒红。改用 `dumpsys window` 的焦点窗（会话页消失 + MainActivity 拿到焦点），它不受弹窗遮挡。
  ⑫ **环境判定要把 FAILED 也当终态观测**：坑⑩的判定起初只轮询 `CONNECTED`，实测 `capture_ok 20:20:32 → CHECKING->FAILED 20:20:48` 而轮询在 20:20:47 退出，1 秒竞态把"结构性环境失败"误报成"异常需人工"。判据必须同时观测 `CONNECTED` 与 FAILED 转移，谁先到算谁。
  ⑬ **grep 字符类漏写量词=恒假判据**：坑⑫的修复里我写成 `grep -cE "ice state: [A-Z]->FAILED"`——`[A-Z]` 只吃**一个**字符，真实行是 `ice state: CHECKING->FAILED`，所以这条判据永远返回 0。run5 因此把本该 `SKIP(环境)` 的媒体段报成 FAIL（同一份 logcat 里 `20:28:09` 明明白白有那条 FAILED：旧正则 0 命中、新正则 1 命中）。改回 `[A-Z_]+->FAILED`，并进 t0 静态守卫（harness 里出现 `[A-Z]->` 即红）——这是铁律②"判据必须真能匹配"的正则版本，比"字符串是否存在于源码"更隐蔽，因为它看起来完全合理。
  ⑭ **`dumpsys window` 的 `mFocusedApp` 会滞后，不能当"页面还在"的判据**：`sess_page` 旧口径统计 `mCurrentFocus|mFocusedApp` 两个键，实测 B 的会话页 20:52:29 已 CLOSE、`mCurrentFocus` 已回落到 `MainActivity`，而 `mFocusedApp` 仍挂着 `SessionActivity` 的 ActivityRecord → 「观看端会话页未退出」恒真，锁屏保活那条 AC-6 判据一直没测到东西。焦点判据只准写 `grep -cE "mCurrentFocus=Window.*SessionActivity"`（e2e.sh 的 T4-6 本来就是这口径，drive-test.sh 抄漏了），进 t0 守卫。
  ⑮ **负向断言（"零 bye"）的基线必须取在窗口起点**：drive-test.sh 原先在锁屏前才 `endlog` 取基线，而 ICE 失败看门狗早在接通后 ~30s 就发了 bye（实测 20:52:15 FAILED → 20:52:28 `signal: bye` → B CLOSE），基线把这段吞掉 → 增量恒 0 → 恒绿。现在基线在 `signal: accept` 之后立刻取（`EA0/EB0`），锁屏窗口再单独取一份（`E0A/E0B`）；并且 AC-6 整块加了**前置条件**：锁屏瞬间共享服务在跑 + 观看端焦点在会话页，才做四条硬断言，否则显式 `SKIP(环境)` 并让整层返回 2——本机 ICE 连不通时会话根本撑不到锁屏，这时候记 PASS 是撒谎、记 FAIL 是冤枉产品。
  ⑯ **中文文案里 `$VAR` 紧跟全角括号会当场崩脚本**：`echo "…$APK（设置 APK_PATH）"` 在 UTF-8 下 bash 把 `（` 的首字节算进变量名，`set -u` 直接 `unbound variable`（实测 drive-test.sh:254 整段 AC-6 就这么死掉，白跑一轮 8 分钟）。一律写大括号形式；已进 t0 守卫（扫 `scripts/*.sh` 里变量引用后接非 ASCII 的行）。
  ⑰ **采集进程"活着"不等于在采集**：v0.2.56 那一轮 adb 传输掉线后，`adb -s 7c6a9986 logcat` 停在 `- waiting for device -`，ps 里进程还在、输出文件从 19:35 起一个字节没写，整轮 0.2.56 客户端日志白丢。复现期采集改为 `scripts/repro-capture.sh`（`start` 起 ssh `tail -F` 自愈重连 + 真机 logcat，`check` 只做三件事：ssh/adb 各打一次哑铃探传输、state 里两路日志的行必须都在、文件字节数是否在涨），**复现开始前必须 check 到 READY 才让人动手**。
    反向实测：故意写一份缺 `device` 行的 state → `check` 必须 rc=2；第一版没有这两条 `saw_*` 守卫时，它因为变量为空整段跳过设备分支、照样打 READY（=⑰ 本身的坑，已进 t0 守卫）。无人操作时"零增长"只报 WARN 不判死——静默不是断链，拿它当 FAIL 会让体检变成天天喊狼来了。
  模拟器只验信令/服务/UI 状态，媒体与手感的结论仍以真机为准。宿主 load 高时两台 qemu 会饿死 app 主线程（实测 `ANR in … Waited 5002ms for FocusEvent` → 系统杀进程），此时 ICE/`viewer-ready` 变红属环境而非产品，drive-test.sh 会把 ANR/杀进程计数打在媒体断言之前。
  诚实边界：坑⑩里「ICE 连上→媒体硬断言」这条分支本机**从没跑过**（没有可路由的 relay 就没有 CONNECTED）。要让媒体段重新具备判定力，得先在本机起一个真 TURN，并把 dev server 的 `TURN_HOST` 指到模拟器可达的地址（一般是宿主在 SLIRP 里的 `10.0.2.2`，需自行验证）；做不到之前，媒体结论只能来自真机双端。**同理，AC-6 锁屏保活（坑⑮）本机也只验证了"判据不再是空的"，没验证过"锁屏真的不断连"——那要等真机双端。**
- T4 断言的五条铁律（v0.2.56 复盘：e2e.sh 里曾同时存在**恒真**与**恒假**两种空断言，绿了半年）：
  ① `scripts/ui.py has` 只打印 YES/NO、**恒返回 0**，所以 `ui has X && OK=YES` 是恒真；只有 `[ "$(ui has …)" = "YES" ]` 才是断言（drive-test.sh 全程用后者）。
  ② 判据字符串必须先在代码里 grep 得到——`"accept sent"` 从来不存在，用它做接听判据等于恒假，重试和兜底钩子全在白跑。接听的真判据是**主叫端** logcat 的 `signal: accept`（观看端 `accept()` 在 `SessionActivity:596` 发 `"accept"`，主叫 `MainActivity:278` 收到就打印），来电同理用 `signal: incoming`；两者都不占 UI dump 的时间。这条现已机器化进 t0：harness 里每个 `signal: X` 的 X 都必须在 `app/app/src/main/java` 里能找到字面量，找不到就红（坑⑬）。
  ③ 断言顺序必须符合真实调用顺序：客户端是**先投屏授权、后发邀请**（`MainActivity` 的 `invite` 只在 MediaProjection 回调里发），所以 `一起玩 → Start now → invite sent → 来电 → 接听` 才是唯一可过的顺序。
  ④ **环境依赖的断言必须自带判定**（v0.2.56 t4 复盘第二条）：判据若在某类机器上永远为假（如坑⑩的双模拟器 ICE），它和恒真断言一样有害——既拦不住回归，又把真信号埋在一串红里。规则：先观测一个可证伪的前置条件（ICE 是否 CONNECTED），过不了就显式记 `SKIP(环境)` 并让整层返回 2，**永远不折算成 PASS**；前置条件之外的失败形态（卡死、没握手、被杀）保留为真失败。
  ⑤ **状态源必须无滞后、负向断言必须自证窗口起点**（v0.2.56 t4 复盘第三条，run5/drive-run1 各中一枪）：判"页面还在"只看 `mCurrentFocus`（`mFocusedApp` 会停在已 finish 的 record 上，坑⑭）；判"没发生过 X"必须把基线取在窗口起点而不是临检查前（坑⑮，否则基线自己把 X 吞掉）。这轮新进的三条 t0 静态守卫就是这两条铁律的机器版：`[A-Z]->` 单字符类禁令、`signal: X` 的字面量必须存在于源码、`sess_page` 只准统计 `mCurrentFocus=Window`——各自回滚即红（已实测）。

## 3. 用例放哪、怎么加

### Kotlin（JVM 单测，`app/app/src/test/`）

被测对象是「提取出的纯逻辑」：状态机/引擎返回 Decision/Action，Android 壳（Activity/Service/View）只留胶水。加用例模板：

```kotlin
class FooMachineTest {
    private var now = 10_000L                      // 假钟：一切时间行为可断言
    private fun machine() = FooMachine { now }
    private fun advance(ms: Long) { now += ms }

    @Test
    fun `节流边界 2999压住 3000放行`() {
        val m = machine()
        m.fire()                                    // 第一次放行
        advance(CtlConsentMachine.TOAST_THROTTLE_MS - 1)
        assertTrue(m.fire().isEmpty())              // 2999：窗内
        advance(1)                                  // 恰好 3000：放行
        assertEquals(1, m.fire().size)
    }
}
```

注意：
- 测试方法名里**不能有 `.`**（反引号名限制），`peer.gone` 要写成「peer离线」。
- 边界断言前先算单精度：`0.58f - 0.5f = 0.07999998f < 0.08f`（会向下取整到限内）。
  拿不准就用 `node -e "console.log(Math.fround(0.58)-Math.fround(0.5))"` 验证。
- 阈值常量从被测对象引用（如 `OverlayGestureEngine.GHOST_FADE_MS`、`CtlConsentMachine.TOAST_THROTTLE_MS`），不要复制字面量；
  提取对象若还是行内魔数（如 GestureIntent 的 0.04/0.96），先提出为 companion 常量再写用例；
  同时保留一条「常量本身 == 线上值」的锁定断言（见 OverlayGestureEngineTest / CtlConsentMachineTest）。

### JS（`server/test/*.test.mjs`，node:test 内建）

```js
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { PairCodeBook } from '../src/pairing.js';

test('TTL 边界：expires 当刻仍有效 +1ms 失效', () => {
  let now = 1_700_000_000_000;
  const book = new PairCodeBook({ now: () => now, ttlMs: 1000 });
  // ...
});
```

服务端纯逻辑一律**注入时钟**（`now`），不 sleep；消息字面量/日志模板留在 index.js 调用方，提取层只返回决策对象。

## 4. 黑盒（T3）

`server/test/run-blackbox.sh` 起临时实例。加协议用例：
- 时序/鉴权/限速类 → 扩 `protocol-test.cjs`（对抗）或 `smoke-test.cjs`（主链路），两者都支持 `SIG_URL` 环境变量；
- 纯函数决策 → 优先写成 `*.test.mjs` 单测，别起进程。

## 5. 覆盖率

```bash
bash scripts/test.sh --coverage
```
- Android：`-Pcoverage` 门控 jacoco（默认链路零下载、离线安全），`JacocoReport` 限定 `com/soul2soul/app/{session,signaling,util,webrtc}`。报告：`app/app/build/reports/jacoco/`。
- Server：`node --test --experimental-test-coverage`。报告在 `server/` 输出。**黑盒子进程（T3 起的 index.js）不计入**——index.js 主体由 T3 行为保障，不 instrument。
- kover 未引入（评估结论见 OPEN-QUESTIONS）。

诚实的覆盖率预期：提取出的纯逻辑类 90%+ 行覆盖；app 整体 15–30%（Activity/Service/WebRTC 壳层留给 T4 与真机）；server pairing/turn 模块 70–85%。

实测基线（2026-10，v0.2.23 首跑 `--coverage`，jacoco 经 Aliyun 镜像下载成功）：

| 对象 | 行覆盖 |
|---|---|
| GestureIntent / CtlConsentMachine / SignalRouter / VersionManifest / SignalEnvelope / StrokeMapping / IceServerParser | 100% |
| OverlayGestureEngine | 96%（143/149） |
| server/pairing.js | 100%（分支 95.2%） |
| server/turn.js | 100% |
| app 四包整体（session/signaling/util/webrtc） | 10.8%（289/2674；壳层未覆盖所致，低于原 15–30% 预期，如实记录） |

## 6. T4（E2E）前置

- `adb devices` ≥ 2 台且均已 boot（当前常态是 Mi5 + Mi15 真机或模拟器双开）；
- 服务器可跑（T4 自带起服逻辑时遵循 S2S_DATA_FILE）；
- 不满足时 test.sh 报 SKIP 而非 FAIL——SKIP ≠ 通过，发版前人工确认。

弱网恢复与语音质量采样步骤、日志字段说明和建议矩阵见 [NETWORK-QUALITY.md](NETWORK-QUALITY.md)；当前未完成的真机事项及维护记录见 [VALIDATION-TODO.md](VALIDATION-TODO.md)。没有两台真机结果时，只能报告单测/协议测试通过，不能声称弱网恢复或语音听感已验证。

## 7. 回归来源约定

每条「真机踩坑修复」版本都应有对应锁定用例（附录 A 有映射）：
- 多指污染 → OverlayGestureEngineTest（首指跟踪）
- 边缘手势误判（0.92 起点上滑误触 HOME）→ GestureIntentTest
- 授权跨会话残留 / 乐观写回滚 → CtlConsentMachineTest
- 迟到 bye 误杀新页面 → SignalRouterTest
- 内联版本事故（versionCode=0）→ VersionManifestTest
- 配对码爆破 → PairCodeBook 单测 + protocol-test.cjs 对抗
- 涂鸦「完成」退出丢入口球（v0.2.44）→ OverlayBallWiringTest（源码扫描配对不变式）+ t0 悬浮窗 addView/removeView 配对守卫
- 审计第二轮 GAP 批次（v0.2.45）→ AuditRound2WiringTest ×7：操控横幅谎报/ctl_denied 死模式、锁屏横幅丢包卡死、来电取消通知残留、找回通知永生、文字静默丢信、表情面板返回退页
- 折叠通知按钮消失（v0.2.46）→ t0 守卫：addAction(0, 禁纯文字动作按钮
- 表情面板分组（v0.2.48）→ EmojiPanelCatalogTest：四组目录存在、Android7 字体缺字（🥰🥺🫶🫂🧡）禁入、文字默认码点（❤☀❄✌❣☝）必须带 FE0F——删组或塞缺字即变红
- 键盘开着点😊（v0.2.49）→ AuditRound2WiringTest：setEmojiPanel 内 clearFocus 必须先于 hideSoftInputFromWindow（MIUI 键盘回弹会把面板盖到键盘背后）
- 发送收表情墙（v0.2.50）→ AuditRound2WiringTest：sendText 内 setEmojiPanel(false) 必须排在 if (!sent) 成功校验之后（失败/断网先 return，草稿+面板原样保留）
- 息屏断连根修（v0.2.51）→ AuditRound2WiringTest：wakelock acquire 必在 startForeground 之后+带 WAKELOCK_TIMEOUT_MS 超时；onDestroy/unpaired 必 release；PresenceService FGS 类型 specialUse 禁 dataSync（Android14 每日时长上限会定时杀常驻连接）
- 电池豁免只问一次缺口（v0.2.51）→ AuditRound2WiringTest：maybeAskBatteryExemption 必须走 batteryPromptAt 时间戳节流，出现 askedBattery 即变红
- 主叫呼叫页状态机（v0.2.52）→ AuditRound2WiringTest L4：peer.offline 空 reason 走 ringback+OFFLINE_PROMPT，不得再直调 showLeaveMessageDialog（该方法已删，出现即变红）
- 离线错误分支不进留言漏斗（v0.2.52）→ AuditRound2WiringTest L5：peer.offline else 臂（too_fast/not_authed）必须 NONE+toast(peer_offline)，块内不得出现 MessageActivity
- overlay 可见性单点收口（v0.2.52）→ AuditRound2WiringTest L6：`outgoingOverlay.visibility` 全 MainActivity 仅 1 处赋值且在 updateUi 内（多入口多出口漏收=横幅残留）
- 呼叫出口全清（v0.2.52）→ AuditRound2WiringTest L6：declined/bye/call.canceled/unpaired/local.sessionEnded/local.sessionLive 各 case 臂必含 endOutgoing()；accepted 臂必含 ringback 清理+phase=NONE；endOutgoing 必含 callTimeout+ringback 双 removeCallbacks；媒体超时臂含 `gen == callGen` 守卫
- 连接状态胶囊（v0.2.53）→ ConnStatusChipTest L1：Presence.onState 两分支都落 connState 且广播 local.connState；L2：MainActivity handleSignal 有 `"local.connState" -> updateUi()`；L3：connChip/connDot 取色各一处且都在 updateUi 内（单点收口，散写即红）；L4：conn_connecting/offline/connected_unpaired/online/offline 五态文案全引用
- 操控启动无感化（v0.2.54）→ SeamlessControlStartTest S1：PresenceService accepted 分支体不得出现 startActivity（加回强拉主页即红）；S2：同分支必保留 ScreenShareService.start + MainActivity.acceptedReceived=true（起流不能随跳页一起删）；S3：MainActivity onResume 窗口内必用 ScreenShareService.isLive() 兜底并 endOutgoing（防无感后回前台卡"呼叫中"）
- 断连不可解释（v0.2.55）→ SessionEndDiagTest D1：WebRtcClient.close() 必调 reportSessionEnd() 且走 SelfCheck.reportSession；D2：notifyEnded 必 noteEnd(reason)（first-wins，否则只剩 local_close）；D3（v0.2.56 反转）：被控端 ACTION_SCREEN_OFF 分支必报 screen_off 事件，且**分支内不得出现 stopSession/postDelayed**（screenoff 只走 DataChannel，服务器看不见）；D4：finishWithCleanup 必按 sendBye 分注 user_hangup/remote_bye；D5：renderer 必挂 SurfaceHolder.Callback 报 surface_created/destroyed + addSink 处报 sink_attached + 观看端连续零帧报 video_stall；D6：sendDiag 必判 isConnected 且 detail 必截断（禁止把业务内容带进诊断帧）
- 观看端入口球拖不动（v0.2.55）→ ViewerBallDragTest B1：btnBall 点击旁必装 setupBallDrag()/setOnTouchListener；B2：必须用 scaledTouchSlop 分点击与拖动且未拖动时 performClick()（保无障碍点击）；B3：clampBallIntoParent 必钳制 translationX/Y 且父未测量时跳过，父 layout 变化回调里必须重新钳制（转屏后球不能留在屏外）
- 锁屏不再断连（v0.2.56，推翻 PRD FR-8 原设计）→ LockScreenKeepAliveTest L1：`screenOffStop`/`SCREEN_OFF_TIMEOUT_MS`/`screen_off_timeout` 三个符号在 ScreenShareService 全文件不得再出现（加回看门狗即红）；L2：暂停/恢复对称——SCREEN_OFF 分支仍须 revokeOnScreenOff + sendScreenStatus("screenoff")，SCREEN_ON 分支仍须 sendScreenStatus("screenon")；L3：`peer_screen_off` 文案不得再写「自动结束」且须给出路（语音）；L4：video_stall 判定必被 `!remoteScreenPaused` 门控，setRemoteScreenPaused 恢复侧必清 streak，screenoff/screenon 两条 UI 路径各自置位复位（单一收口）
- F/G 采集盲点（v0.2.56）→ PiPLockScreenGuardTest L1：onUserLeaveHint 内 `isInteractive` 判断必须排在 enterPictureInPictureMode 之前（锁屏触发的小窗会拆 surface）；L2：`pip_enter`/`pip_exit` 双向都必须在 onPictureInPictureModeChanged 里报（只加判据不改行为，surface 重挂等日志证据）；L3/L4：两处启动失败 catch 各带一条 diag.event（fgs_start_failed / webrtc_start_failed）且 detail 只放异常类名，起流失败还须先 noteEnd 抢在 local_stop 之前（否则服务器收到一句谎报）；L4b：PresenceService.onState 会话期报 signaling_down/signaling_up 且以 Presence.sessionBusy 为门（待机期不产噪声）
- 观看端自己的锁屏被报成"画面卡死"（v0.2.57，源自 10-17 17:43~18:02 双真机日志）→ LockScreenKeepAliveTest L5：`video_stall` 判定必须再被**本端** `localRenderPaused` 门控（surface  destroyed→created 期间解码没有渲染面，零帧是自己造成的），恢复侧清 streak 并开"等首帧"窗口；surfaceCreated/surfaceDestroyed 两处必须成对调 `setLocalRenderPaused(false/true)`（回滚任一侧即红）；必须存在正面判据 `video_resumed`（只有 stall 分不清"流停了"与"帧回来了但没画出来"），且窗口在出帧时关闭（不能每帧重复上报）。
  ⚠️ 同批日志还否证了两条旧假设：① `surface_destroyed` 之后**每次都有** `surface_created`，所以"重挂 surface"不是 G 的解法；② `sink_attached` 位于 `onRemoteVideo`、按 track 只发一次，surface 重建不会让它再出现——**拿它的缺席当黑屏判据是恒不成立的空判据**（PROTOCOL §2.3 已校正）。门控挂 surface 而非 `onPause`：小窗不销毁 surface，用 onPause 会把 PiP 期间的真卡死一起放过。
- 秒断无从判定（v0.2.57 候选，#51/#55）→ PeerSignalProbeTest P1/P2：`bye`/`peer.gone` 与 `PeerEnded` 两条拆除路径都必须在 `stopSession`/`finishWithCleanup` **之前**报 `peer_signal`（ice 取当时实况、detail 分 `sig=gone|bye`）——顺序就是证据，拆完 ICE 只会是 `CLOSED`，"是不是信令假死拆的"就永久无法回答；P3：事件名必须已登记进 PROTOCOL §2.3（服务端日志出现了却没人知道怎么读=白埋）。把上报挪到拆除之后用例即红。
- t4 锁屏判据（v0.2.56）→ `scripts/drive-test.sh` AC-6 用**实况**而非日志字符串：被控端 `dumpsys activity services <pkg>` 里 ScreenShareService 的 ServiceRecord 还在 + 观看端 `dumpsys window` 焦点仍是 SessionActivity，解锁 10s 后前者再验一次。
  ⚠️ 反面教材：原先写成 `logcat | grep -c "signal: bye"`，而客户端**从不打印**这个字符串，`grep -c` 恒为 0 → 无论会不会话被拆都用例变绿。
- 采集链路自己也会产空证据（v0.2.57）→ t0 守卫：`scripts/repro-capture.sh` 的 `check` 必须同时有 `saw_srv`/`saw_dev` 两条 state 缺行断言（缺任一路日志必须 rc=2，删一条守卫 t0 即红，已实测回滚变红）。判据分两层且不许混：**传输**用 ssh/adb 哑铃探（能抓住"logcat 停在 waiting for device"这种进程在、文件不动的死法），**流量**只用文件字节增量看、静默期一律 WARN——把"没人操作"报成 FAIL 只会让人忽略体检。
  **反向断言必须证明"能变红"**：用桩 adb（有记录→1/1，无记录→0/0）各跑一遍再登记，否则等价于没测。

Service 窗口接线这类 JVM 跑不了的代码，锁定用例的形态是**源码扫描测试/t0 grep 守卫**（把修复
回滚用例必须变红才算锁定）。审计流程本身的规范（状态机矩阵、5 维度清单、gate 登记制）见
`docs/AUDIT.md`，矩阵在 `docs/AUDIT-MATRIX.md`（t0 打印空格数）。

## 附录 A：用例矩阵映射

| 被测单元 | 边界/回归用例 | 对应 AC / 版本 |
|---|---|---|
| GestureIntent (18) | BACK x=0.04/0.041、0.96/0.959；\|dy\|=0.06、\|dx\|=0.12；HOME y=0.96/0.959；dur 399→HOME/400→RECENTS；0.93 上滑→null；单点→null；resolveGlobalAction 4 组合 | FR-7 / v0.2.19、v0.2.20 |
| OverlayGestureEngine (16) | 轻点 399/400；位移=slop 不启动；多指混入/首指抬起；收笔双发；ghost 599/600/601；采样 12.001px/61ms；第 65 点丢弃；长按 649/650；4 位截断+钳制；尾迹解耦 | FR-5/FR-7 / v0.2.18 双影、v0.2.21 多指 |
| CtlConsentMachine (13) | 乐观回滚；锁屏收回一次不发重复；3s/4s 节流两侧边界；入队后收回拦截；新会话归零；无障碍消失分支 | FR-7 / v0.2.18 会话级收回、v0.2.20 对话框 |
| SignalRouter (9) | sdp/ice Buffer↔Dispatch；迟到 bye 忽略；call.canceled 双语义；ctl_denied×非操控；screenon×!live | §4 状态机 / v0.2.10 竞态 |
| VersionManifest (9) | 缺字段→0；非 JSON→null；降级→false；remote==local→false | v0.2.16 内联事故 |
| SignalEnvelope (4) | hello 无 token 省略字段；type 信封 | §2 协议 |
| StrokeMapping (11) | video=0 回退；0.05 容差边界；往返一致；整屏尺寸还原；非有限坐标拒绝 | FR-5 / §5 |
| IceServerParser (7) | 多 urls；urls 缺失跳过；凭证携带 | §2.3 |
| IceRestartBudget (3) | 单次最多 3 次；稳定 30 秒后清额度；未使用时不误报重置 | ICE 恢复额度回归 |
| RtpQualityWindow (3) | 累计计数换算窗口丢包/隐藏音频时长；首样本/计数重置/缺失统计不伪报 0 | 弱网与语音质量诊断 |
| PairCodeBook (9) | +4999/+5000ms；旧码作废；第 5/6 次尝试；TTL ±1ms；sweep；self_pair | §2 防爆破（repo 常量） |
| MessageBook (10) | 限速 999/1000ms；发件人隔离；text 500/501；voice 校验（bad id/时长）；list 时序+拷贝+过期；ack 只删自己；容量溢出丢最旧；forgetDevice 双向；sweep TTL±1ms；self/空收件人 | 离线留言（未发版） |
| turn (5) | 固定 HMAC 向量（openssl 交叉验证）；udp+tcp；username 过期秒 | §2.3 |
| InboxStore (6) | fromJson 语音字段保留/缺 id→null/kind 缺省；按 id 去重；received 排 mine+时序；conversation 合并时序；remove 只删收到的；回调次数+重复 addAll 不触发 | 离线留言（未发版） |

> 括号内为用例数（原基线 Kotlin 93；新增 ICE/媒体窗口 6 项后为 Kotlin 99 / node 24 / 黑盒 48）。
