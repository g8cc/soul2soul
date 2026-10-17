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
