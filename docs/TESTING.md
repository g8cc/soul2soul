# Soul2Soul 测试体系（TESTING）

## 1. 分层定义

唯一入口：`scripts/test.sh`（分层 harness）。

| 层 | 内容 | 命令内部 | 依赖 | 默认执行 |
|---|---|---|---|---|
| T0 | 静态检查 | `bash -n scripts/*.sh`；`node --check` 全部服务端 JS；AndroidManifest XML 良构（python minidom）；package.json JSON 校验 | 无 | ✅ |
| T1 | Android JVM 单测 | `gradle -p app :app:testProdDebugUnitTest` | JDK17 + Gradle 8.7 | ✅ |
| T2 | Server 单测 | `node --test 'test/*.test.mjs'`（pairing/turn 纯逻辑） | node ≥ 22 | ✅ |
| T3 | 协议黑盒 | 随机空闲端口起临时服务器（`S2S_DATA_FILE=$(mktemp)`）→ 等 `/health` → `protocol-test.cjs`（17 项）+ `smoke-test.cjs`（15 项）→ trap 清理 | node | ✅ |
| T4 | 模拟器/真机 E2E | 复用 `scripts/e2e.sh`，需 adb + ≥2 台已 boot 设备 | adb、设备 | ❌ 门控 |

关键保证：**测试永不写 `server/data/pairings.json`**（T3 用临时数据文件），跑完无残留 node 进程。

## 2. 用法

```bash
bash scripts/test.sh              # T0–T3，秒级到分钟级，日常默认
bash scripts/test.sh --tier t2    # 只跑某一层：t0|t1|t2|t3|t4
bash scripts/test.sh --coverage   # 追加覆盖率（见 §5）
bash scripts/test.sh --e2e        # 探测到 adb + 2 台 boot 设备才执行 T4，否则 SKIP
```

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

## 7. 回归来源约定

每条「真机踩坑修复」版本都应有对应锁定用例（附录 A 有映射）：
- 多指污染 → OverlayGestureEngineTest（首指跟踪）
- 边缘手势误判（0.92 起点上滑误触 HOME）→ GestureIntentTest
- 授权跨会话残留 / 乐观写回滚 → CtlConsentMachineTest
- 迟到 bye 误杀新页面 → SignalRouterTest
- 内联版本事故（versionCode=0）→ VersionManifestTest
- 配对码爆破 → PairCodeBook 单测 + protocol-test.cjs 对抗

## 附录 A：用例矩阵映射

| 被测单元 | 边界/回归用例 | 对应 AC / 版本 |
|---|---|---|
| GestureIntent (18) | BACK x=0.04/0.041、0.96/0.959；\|dy\|=0.06、\|dx\|=0.12；HOME y=0.96/0.959；dur 399→HOME/400→RECENTS；0.93 上滑→null；单点→null；resolveGlobalAction 4 组合 | FR-7 / v0.2.19、v0.2.20 |
| OverlayGestureEngine (16) | 轻点 399/400；位移=slop 不启动；多指混入/首指抬起；收笔双发；ghost 599/600/601；采样 12.001px/61ms；第 65 点丢弃；长按 649/650；4 位截断+钳制；尾迹解耦 | FR-5/FR-7 / v0.2.18 双影、v0.2.21 多指 |
| CtlConsentMachine (13) | 乐观回滚；锁屏收回一次不发重复；3s/4s 节流两侧边界；入队后收回拦截；新会话归零；无障碍消失分支 | FR-7 / v0.2.18 会话级收回、v0.2.20 对话框 |
| SignalRouter (9) | sdp/ice Buffer↔Dispatch；迟到 bye 忽略；call.canceled 双语义；ctl_denied×非操控；screenon×!live | §4 状态机 / v0.2.10 竞态 |
| VersionManifest (9) | 缺字段→0；非 JSON→null；降级→false；remote==local→false | v0.2.16 内联事故 |
| SignalEnvelope (4) | hello 无 token 省略字段；type 信封 | §2 协议 |
| StrokeMapping (9) | video=0 回退；0.05 容差边界；往返一致 | FR-5 / §5 |
| IceServerParser (7) | 多 urls；urls 缺失跳过；凭证携带 | §2.3 |
| PairCodeBook (9) | +4999/+5000ms；旧码作废；第 5/6 次尝试；TTL ±1ms；sweep；self_pair | §2 防爆破（repo 常量） |
| turn (5) | 固定 HMAC 向量（openssl 交叉验证）；udp+tcp；username 过期秒 | §2.3 |

> 括号内为用例数（2026-10 时点，合计 Kotlin 85 / node 14 / 黑盒 32）。
