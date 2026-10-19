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
    复现日的真机歧义（同一条死法的变体）：本机除米10 外还常插着 Mi5 开发机，`pick_serial` 只在**恰好一台**真机时才自动选，两台在场它原本返回空、`start` 却把原因说成"未接真机"，人就去查线、其实线好好的。现在 `start` 在这种情况列出 `SERIAL=<序列号>` 候选并明写"客户端侧已放弃"，`check` 紧跟 rc=2（用假 adb 塞两个序列号实测过这两条输出）。**复现前固定写死 `SERIAL=7c6a9986 bash scripts/repro-capture.sh start`，别让它猜。**
  ⑱ **harness 自己的坏路径没有静态形状可看（10-19 手跑 ui.py 才撞见）**：`ui tap` 的"点完确认目标消失、没消失就重试"是 t4 防 `input tap 被吞` 的唯一机制。6c0facb(10-17) 写这段时把 `[b for _, _, c in _match(…) if c]` 的名字写漏 ⇒ 只要点完仍有可点节点就抛 `NameError`、`ui.py` 退 3，而 e2e 每处都写 `ui tap … || true` ⇒ **从那天起点击校验/重试整段静默失效，t4 一直全绿**（`_match_one` 仍优先 clickable 节点，所以点还是落在按钮上，损失的是"被吞了就再点"这层保护）。改成 `any(c for _, _, c in _match(…))`，并把 `scripts/ui_selftest.py` 挂进 t0：打桩 `_match_one/_match/find_nodes` 跑三种情形（点完即消失→True 且只点一次；点完还在→重试到消失、共两次；从未出现→False 且一次都不点）。情形二在修复前当场红（已实测回滚变红：把 `scripts/` 连同这行一起复制到 `/tmp` 沙箱、只把 `ui.py` 抽回旧写法，沙箱里 `test.sh --tier t0` 打印该守卫并 FAILED，换回修复即 PASS——用沙箱是因为当时 t4 正在用真 `ui.py` 跑，改在跑的那份上会让"红"与"t4 挂"混成一件事）。口径：**"判据看起来合理"不等于"判据在跑"——保护机制也要有可执行自检，grep 抓不到 NameError。**
  衍生操作口径：**抽掉即红必须走真 gate（`test.sh --tier t0`），不是只手跑自检脚本**；若那份 harness 文件此刻正被 t4 使用，就把 `scripts/` 复制到 `/tmp` 沙箱去改坏（`test.sh` 的 `ROOT="$PWD"`，配 `app/server/docs` 三个软链即可跑 t0，t0 全程只 grep），别在跑着的那份上动刀——10-19 用 `git checkout scripts/ui.py` 做回滚实测时把自己的未提交修复一起清掉了。
  ⑲ **「跳过了几条」必须是累加，不是写死的数字（10-19 复核 t4 输出撞见）**：那轮汇总行印 `SKIP_ENV=2`，屏上却有**三行** `SKIP(环境)`——`stop_tap` 那条打了 SKIP 却没进账；`drive-test.sh` 里两处 `ENV_SKIP=2` 更是**赋值覆盖**（AC-6 那条自己写着"四条断言"却只记 2）。这不是排版问题：发版说明里"媒体未本机验证"必须能落到"具体哪几条没判"，数字与屏上行数对不上时，读的人会把它当成"少数几条之外都已判定"。改法：每条被环境挡掉的**断言**自己 `ENV_SKIP=$((ENV_SKIP + n))`（计数单位是断言数而不是 SKIP 行数——`drive-test.sh` 里一行 `SKIP(环境)` 覆盖的是 ICE+onLive 两条、AC-6 那行覆盖四条 ck），ICE 不可路由那一段横幅不再记账（它不是断言）；t0 守卫 `ENV_SKIP=<数字>`（除 `=0` 初始化）在 e2e.sh/drive-test.sh 里出现即红（沙箱里把一条改回 `ENV_SKIP=1` 实测红）。**观测确认**：改后同一条路径重跑 t4，`== E2E: PASS=9 FAIL=0 SKIP_ENV=3 ==` 与屏上三行 SKIP 一一对上。
  模拟器只验信令/服务/UI 状态，媒体与手感的结论仍以真机为准。宿主 load 高时两台 qemu 会饿死 app 主线程（实测 `ANR in … Waited 5002ms for FocusEvent` → 系统杀进程），此时 ICE/`viewer-ready` 变红属环境而非产品，drive-test.sh 会把 ANR/杀进程计数打在媒体断言之前。
  ⑳ **坑⑲的镜像：断言会"消失"而不是"变红"（10-19 借线上 TURN 让本机第一次判得出媒体时撞见）**。`stop_tap` 那条整个写在 `if [ "$ICE" != "OK" ]` 里——理由正当（点它会拆掉 A 的会话，媒体通就必须排在同屏/笔迹之后），但 ICE=OK 的那条路里它既不计 PASS 也不计 ENV_SKIP，于是汇总行悄悄少一条（实测 `PASS=9` 而应为 10、`SKIP_ENV=0`）。这类缺陷比恒真/恒假更隐蔽：**两条分支各跑一次得到的行数不一样，而"该有几条"没有独立的真值来源可对照**。改法是把断言收成函数 `check_stop_tap()`，两个分支各调一次（ICE=OK 时挪到 T4-5 之后），t0 守卫直接盯"两处调用都在"（沙箱里删掉 `= OK` 那条实测红）。口径：**凡因副作用要挑执行时机的断言，必须证明它在 ICE 两种结果下都被判定过**；只写在一条分支里=另一条分支静默丢格子。
  ㉑ **判据直读了平台根本不提供的字段，而它的缺省值恰好落在告警分支上（10-19 由 t4 媒体段第一次出数才撞见）**。观看端"画面卡死"写的是 `inboundVideoFps <= 0.0`，而 `framesPerSecond` 在本版 libwebrtc 的 `outbound-rtp`/`inbound-rtp` 上**不存在** ⇒ 直读得到 -1，每 10s 给 streak 加一格，30s 后必然 `video_stall`：线上 6 条（`ice=CONNECTED`、`rttMs=84/59/91`）全是这条空判据产出的，而它们一直被读成"她那边确实卡"。同一类的第二例是 `available > 0 && available < required×72%`：`> 0` 看着像在防"未知"，其实防不住**读得到但还没稳**的值——带宽估计器爬坡期钉在 300kbps 初值（实测连两拍 300、触发降档那一刻同一列已是 5.38Mbps），6s 内必攒出一次降档，而降档在 Android 14+ 是致命动作。口径：**任何 `<= 0` / `< 阈值` 形态的判据，落笔前先问"读不到时走哪条支路"**；缺省值 `-1` 必须有独立分支（既不告警也不清零），能用"累计计数区间相减"就别直读平台字段（字段存在性由 libwebrtc 版本决定，不写测试就永远没人发现），而"还没稳的读数"要显式建模成一维（`bandwidthSettled`），不是靠 `> 0` 撞运气。锁定：`RtpQualityWindowTest` 5 条（`framesFps` 的 0/未知/回退/窗口太短）+ `VideoQualityPolicyTest` 爬坡期四条 + `CaptureRestartGuardTest` C4（把 `== 0.0` 退回 `<= 0.0` 实测红）。
  ㉒ **铁律②只防"凭空造的名字"，防不了"真日志的形状和判据不一样"（10-19 抓到，坑⑬的第三条腿）**。坑⑬ 把字符类量词修对之后，`ice state:` 的**成功**分支仍写成字面量 `ice state: COMPLETED|ice state: CONNECTED`，而客户端唯一打印形式是 `Log.d(TAG, "ice state: $previousState->$state")`（`WebRtcClient.kt:725`）⇒ 真实行永远长这样：`D/WebRtcClient( 3791): ice state: CHECKING->CONNECTED`，字面量匹配不到。后果不是红而是**整段判定力丢失**：20 轮轮询全部 0 命中 → `ICE=UNKNOWN` → 媒体断言既不算 PASS 也不算 SKIP，直接落成 `FAIL 媒体段异常：ICE=UNKNOWN FAILED=0/viewer-ready=1 不符合`，而这轮实际是一通 65 秒、`diag.session ice=CONNECTED path=s-s` 的好会话。这条判据在源码里 grep 得到（`ice state:` 确实在），所以铁律②的机器版（`signal: X` 字面量必须存在）也拦不住——它检查的是"名字存在"，不是"形状能命中"。改法三层：① `e2e.sh:278` 与 `drive-test.sh:189` 都用 `ice state: [A-Z_]+->(COMPLETED|CONNECTED)`；② t0 新守卫扫两个 harness 的**代码行**（注释切掉，否则解释"为什么不能这么写"的反例会被自己判红），出现不带 `->` 的 `ice state:` 即红；③ 回滚实测走真 gate：把 `e2e.sh` 那行改回字面形式 → `test.sh --tier t0` 红并定位到 `e2e.sh:278`，改回来即绿。口径：**任何取自日志行的判据，必须至少留一次"真命中"的记录**（写进矩阵或这页），否则它与恒假断言不可区分。
  ㉓ **告警的窗口比它要防的故障还短，就等于把判据灌进日志洪水里（v0.2.63 撤掉 `b=fps0`）**。`qos_bad` 的第一条规则是"这一格 0 帧"，看起来只是单窗口、代价很小；实测双模拟器静置共享（画面本来就静止）⇒ sharer 与 viewer **每 10s 各一条**，一分钟 12 条，而 `DiagBacklog` 的 P0 配额是 150 条——断连积压时它自己就能把队列占满，把真正要保的 `aexit`/`ice_failed`/`net_*` 挤出去（=静默丢证据，与 0.2.59 修掉的 `wake_ok` 顺序 bug 同一类"埋了等于没埋"）。同一件事还有一条更硬的性质：`video_stall` 早就存在且证据更强（连续 3 个完整 10s 窗口 + 排除本端停帧与对方锁屏），两条并存=两个真值来源，读数会互相打脸。改法是**删而不是调阈值**：`QosAnomaly.pick` 只留差分判据（选路变化、丢包、rtt 跳变），第一条 tick 没有 prev 就什么都不报。锁定 `StandbyTelemetryTest` S5a（源码里出现 `"b=fps0"` 即红 + streak 的三个分支必须在）与 `QosAnomalyTest` 两条行为用例；回滚实测：把 fps0 加回去 → 三条同时红（1 条源码锁 + 2 条行为）。**这一格是本机出数才撞见的**（10-19 t4 那轮的 `event=qos_bad detail=b_fps0` 连成一片），单测阶段它一直是"绿的、有道理、看着像在工作"。
  ㉔ **笔迹通路是好的，是宿主 App 把悬浮层藏了（10-19 T4-5 那次 `A 截图差异数: 0` 的真根因）**。当时按"通路坏"一路查：① B 侧确实在画（截图里颜色精确到 `#FF3B5C`，`DrawingOverlayView` 本地层正常）；② B 的 logcat 没有 `annotation dropped`、没有 channel null 告警 ⇒ `sendAnnotation` 认为发出去了；③ 我于是断言"A 上根本没有 canvas 窗口"——**这句是空 grep 造出来的假结论**：`dumpsys window windows` 的窗口头真形是 `Window{hash u0 pkg}`，我 grep 的是 `Window \{`（多了个空格），命中恒 0，于是"窗口不存在"看起来被"证实"了；④ 修对 grep 后窗口在 swipe 期间**在**，但带着 `mForceHideNonSystemOverlayWindow=true`、`isVisible=false`、`mDrawState=READY_TO_SHOW`、`isReadyForDisplay()=false`、`Surface: shown=false alpha=0.0`——attached、drawn、永不合成；⑤ 把 A 顶回自己的 `MainActivity`，同一笔立刻 `changed 4842 bbox (190, 1481, 419, 1626)`（新色 `(255, 98, 125)`）。根因是 Android 12+ 的 `Window.setHideOverlayWindows()` / `HIDE_OVERLAY_WINDOWS`：宿主窗口可见期间，其它 App 的 `TYPE_APPLICATION_OVERLAY` 一律被框架强制隐藏，而 T4-4 为了点掉 MediaProjection 通知恰好把 A 停在**设置页**——`com.android.settings` 就是这么声明的。两层教训：
    - **harness 层**：断言"悬浮层可见"之前必须先把宿主顶回我们自己的页面，且 FAIL 分支要能把"通路坏"和"宿主藏层"分开印（现在打印 `A 上被框架强制隐藏的悬浮窗数=`，>0 即环境非产品）。同一格还捎带一条时序错：旧写法 swipe 完 `sleep 4` 再截图，落在收笔淡出（`StrokeColors.FADE_MS=2000`）之后，结构上恒假 ⇒ 改成**手指还按着的时候截图**（4s swipe、1.5s 后拍；书写中的笔迹不进淡出分支）。
    - **产品层（比测试更重要）**：这不是模拟器专属。她若把 App 切到设置页 / 部分 ROM 桌面 / 银行类 App 前台，涂鸦球和笔迹会**画了但看不见**，而且没有公开 API 能探测到"我被宿主藏了"。已登记为 `AUDIT-MATRIX.md` §1 的一行 `⬜`（不写成 GAP：处置要用户点头——① 只用文案告诉她"当前界面看不到对方的涂鸦"，② 改成把笔迹画进采集帧本身，代价是污染她自己的屏幕）。
    - 口径：**"截图 diff 恒 0"有三种成因——淡出窗口、通路、宿主藏层**，三者读起来一模一样；先证明"这一笔此刻不该淡"，再用 dumpsys 的 `mForceHideNonSystemOverlayWindow` 排除宿主，最后才允许怀疑通路。锁定进 t0：`e2e.sh` 里只要还有"笔迹出现在 A 屏幕"这条断言，就①必须在 `T4-5` 段头之后、`e2e_a_before.png` 之前出现一次 `am start -n "$PKG/com.soul2soul.app.MainActivity"`，②必须出现 `grep -c "mForceHideNonSystemOverlayWindow=true"` 那行诊断，缺任一即红。四种抽法都实测过红：删掉顶回那行 / 把它挪到截图之后 / 删诊断行 / 删断言本身（"顺序"那一半必须用**窗口**判，不能只判"文件里有这行"——`check_stop_tap()` 函数体里也有一模一样的一行且位置更靠前，早期版本就这么恒绿过）。改完复跑：`A 截图差异数 3058`、`t4 PASS=14 FAIL=0 SKIP_ENV=0`（本仓库媒体段第一次既不 FAIL 也不 SKIP），`t0` 同时绿。
  ㉕ **服务器一直在发的 `reason` 被客户端盖成一句通用文案，而"补锁"的第一版测试自己也是假绿的（10-19 配对死路）**。(a) 产品面：`already_paired` 的成因是**对方那台设备上的旧绑定还在**——换机或重装 App 会换 deviceId（`util/Prefs.kt:12-17` 把它存在 App 自己的 SharedPreferences，卸载即抹、重装即变），唯一出路是让**对方**点「解除配对」；而 `MainActivity` 当时把所有 `pair.failed` 都 toast 成「请检查配对码是否正确」，用户照那句话把同一个码再输三遍仍然是死路。信息服务器一直都带着、是客户端把它丢掉的，与坑㉑同一类（字段存在但没人读），只是这次丢的是给人看的那一份。改法：`util/PairFailMessages.kt` 逐条给"原因+下一步"，其中 `SERVER_REASONS` **不是手写清单**——`PairFailMessagesTest` 从 `server/src/pairing.js` 的 `reason:` 字面量与 `index.js` 里 `type: 'pair.failed'` 那两处 send 扫出来对账（服务器新加一个 reason 而客户端没跟上即红），`docs/PROTOCOL.md` 的 `pair.failed` 行补齐七个 reason 并写明"客户端必须逐条给文案"。(b) 测试面：第一版"PROTOCOL 覆盖每个 reason"写的是**整行**含 `` `already_paired` `` 就行，而我给那一格新加的说明文字里**又重复写了这个 token** ⇒ 把枚举格里的它删掉，测试照样绿。这就是假锁：判据没钉在它声称的那一段上。真判据是 `line.split("|")[2]`（第二格）+ 断言该格 `trim().startsWith("`reason`")`（防列位漂移后又到别的格匹配）。口径：**回滚实测要"改坏判据声称的那一格"，而不是"从文件里删掉那个 token"**。(c) 同一轮撞见的第三个坑：**读源码文件的 t1 测试，Gradle 不知道那些文件是它的输入**——手跑 `gradle -p app testProdDebugUnitTest --tests X` 会 `UP-TO-DATE` 整任务跳过，屏幕打 `BUILD SUCCESSFUL` 而测试**一条都没跑**（我据此一度以为 (b) 已经修好了）。gate 里 `test.sh:522` 固定带 `--rerun` 所以不会漏；**手写证伪命令必须带 `--rerun`**，并且别只看有没有 `N tests completed`（那行只在全红时才打）——要确认"真跑了、真加了一条"就读 `app/app/build/test-results/…/<Class>.xml` 的 `tests=`（本轮 4 → 5 才算新用例进了账）。
  ㉖ **实现是"默认保护"，文档却写成了"白名单"——文档的口径也会撒谎，而且撒向最坏的方向（10-19 复核 v0.2.59 §5 时撞见）**。`BacklogTiers.tierOf` 的实际规则是 `else -> TIER_DEATH`：除 `heartbeat`(P1) 与 `qos_tick`(P2) 之外的一切事件名都默认受保护；而 `docs/PROTOCOL.md` 那条写成了「P0（`aexit`/`session_end`/`ice_*`/…）」的**枚举口径**——这正是当时计划里 §5 的写法（照计划把 P0 列成清单，落地时实现选了反过来的默认分支，文档没人回头改）。危害不是"少写几个名字"：`signaling_down`/`screen_off`/`probe_start`/`video_stall`/`stop_tap` **本来就不在那份清单里**，按白名单读日志的人会以为它们在积压超配额时可被淘汰，从而把"这条证据没回来"读成"它当时不重要"；更实际的一条是**以后新加的埋点**——白名单口径会诱使人"忘了加进清单 = 掉进最廉价档 = 断连时被静默丢掉"，而默认分支的失效方向是相反的（忘了写就是受保护）。改法不是补清单，是把文档改成它真正的规则（"默认 P0，只有 `heartbeat`/`qos_tick` 被降级，解析不出事件名的行按 P2"），并留两条锁：`BacklogTierTest` T9（行为：未登记事件名 → TIER_DEATH + 从源码扫"降级名单"必须恰好等于 `{heartbeat, qos_tick}` 且默认分支还在）、T10（文档：那条规则必须含「默认 P0」与「被降级」，且出现 `P0（\`aexit\`` 这种白名单起手式即红）。回滚实测：把默认分支改成 `TIER_QOS` → 6 条红（含 T1/T3 这些老用例，说明默认档一旦降级，积压现场立刻没人保护）；多加一个 `"screen_off" -> TIER_BEAT` → T9 红；把 PROTOCOL 那句退回白名单写法 → T10 红；改回全部绿。口径：**任何"分级/配额/清单"类的文档句子，必须与代码的默认分支方向一致，并且把默认方向本身锁住**——列出的名字只是例外，例外清单要用源码扫出来对账（同 ⑲「跳过了几条必须累加」、②「signal: X 字面量必须存在」这两条同族：手写清单会腐烂，机器扫的对账不会）。
  ㉗ **用例的名字也会腐烂：`…only during a session` 这条锁在代码改了语义之后没跟着改，退化成一条假锁（10-19 复核矩阵 §8「会话中 × 信令 socket 死」那格时撞见）**。`PiPLockScreenGuardTest` L4 写于 0.2.58：那时 `signaling_down/up` **只在会话期报**，所以它的判据是 `src.any { it.contains("signaling_down") }` + `onState.any { it.contains("Presence.sessionBusy") }`。0.2.59 按计划把待机期也接上报（`PresenceService.kt:206-229` 两条分支各一条），于是这句话所保护的语义反了：`any` 会被**待机那一条**满足，把**会话期**那一条整个删掉，用例照旧绿。我用 HEAD 版用例对着这个变异实测过——`tests="6" failures="0"`（不是推断，是真跑）。这类坑和 ㉕(b) 同族（判据没钉在它声称的那一段上），但多一层：**实现变了没人回头改测试名与判据，测试名就变成下一条"白名单式"的谎言**，读代码的人会以为会话期不报。改法是按分支钉死：四条锚点（会话期 up/down、待机期 up/down）各自 `before(anchor, 2)` 取它上面两行，断言会话期那两条的紧邻上文含 `Presence.sessionBusy`、待机期那两条含 `} else if (standbySigDown)` / `} else if (!standbySigDown)`（边沿门必须还在，否则 Doze 里每 30s 一次退避重连会刷成噪声），并要求每条锚点**全文件恰好出现一次**、且落在 `between(onState, "先建立总线订阅")` 之内。回滚实测：删掉会话期 `signaling_down` 那条 → 红 1 条；把待机两条的 `else if (…standbySigDown)` 拍平成裸 `else` → 红 1 条；改回全绿（6 条）。口径：**任何"只在 X 时才做 Y"型的用例，判据必须能区分"X 分支做了"与"非 X 分支也做了"**——`contains(名字)` 级别的整文件扫描对这一类天然无效；功能语义一变，先回头改这一族的用例名与判据，再改代码。
  ㉘ **给守卫做"抽掉即红"实测时，要按它自己声称的语义去变异（10-19 对 v0.2.59 那批 t0 守卫补做计划要求的证伪时撞见）**。逐条实测结果：网络回调注销、`BacklogTiers.trim` 锚点、`take(FLUSH_MAX)`、`FLUSH_MAX_BATCHES ≥2`、emu 独立桌面名——五条都按预期变红；**唯一变不红的是「registerReceiver 必须配对」那条**：把 `unregisterReceiver(screenReceiver)` 整行删掉，`t0` 仍 `rc=0`。根因是它的形状是**按文件存在性**判（`grep -q "unregisterReceiver("`），而 `StandbyProbe` 注册了 screen + doze 两个 receiver、注销两行——删掉任意一行，同一文件里还剩另一行，判据照样满足。也就是说这条守卫的**注释声称"按文件配对"，实际只查"有没有"**，二者在"一文件多注册"时不是一回事。诚实边界（必须先说，否则会把它写成比实际更严重的洞）：**这不是无人看守的漏洞**——t1 的 `StandbyTelemetryTest` S1 用的是 `assertEquals(register 条数, stop 里 unregister 条数)`，同一条变异实测 `tests=12 failures=1`，网本来就在，只是高了一层。所以真正的缺陷是"t0 那条比它自己写的理由弱"，而 t0 是第一道、最便宜、改一行就能跑的那道。修法=两条兄弟守卫（广播 + 网络回调）都改成**计数**（注销条数 ≥ 注册条数即过），报错文本把 `register=N/unregister=M` 打出来（只说"缺配对"的话，读的人还得自己数）。**计数时踩到的第二个坑是子串**：`unregisterReceiver(` 里面**含有** `registerReceiver(`，不加字母锚的话注册数会被算成 4（2 真注册 + 2 注销），守卫立刻**恒红**——恒红和恒假一样是废守卫，区别是它会被人生成"预期红"然后忽略。判据用 `(^|[^A-Za-z])registerReceiver\(`：既排除 `unregister…`，又顺手补上旧选择器 `\.registerReceiver(` 的另一个洞（`Service` 继承 `ContextWrapper`，裸调 `registerReceiver(` 不带点是合法写法，旧写法连"要不要检查这个文件"都不会选中它）。回滚实测四条：删 screen 注销 → 红并打 `register=2/unregister=1`；凭空多注册一个 receiver 不注销 → 红并打 `register=3/unregister=2`；删网络回调注销 → 红并打 `register=1/unregister=0`；恢复 → 全绿。口径：**① 证伪要挑"这条守卫本应拦住的那种坏法"，不是挑最容易变红的坏法**（同 ㉕(b)「改坏它声称的那一格」）；**② 判"配对"这类守恒关系时按条数判而不是按名字是否存在判**（同 ⑲「跳过了几条必须累加」）；**③ 改完守卫要同时验证"正常态绿"和"变异态红"**，只验前者会造出恒红守卫。**另：计数法不是普适的，同一族的「wm.addView 必须有配对 wm.removeView」就明确不改**（当场量过：`AnnotationOverlayService.kt` add=3 / remove=5——拆除路径本来就是共用的，`remove ≥ add` 恒成立，改成计数只会得到一条没有判据的守卫；入口球丢失那类真问题由 `OverlayBallWiringTest` 按语义配对来锁）。判据是"这条守恒关系是不是 1:1"，不是"别的守卫这么写过"。
  诚实边界（10-19 14:37 复跑后改写；此前这段的结论被自己的读数否证了）：坑⑩里「ICE 连上→媒体硬断言」这条分支**已本机可达并且真的出像素了**——借线上 TURN 凭证注进本机 dev server 后，同一轮 t4 里 `diag.session` 报 `ice=CONNECTED path=s-s proj=live liveMs=53199`、T4-4 两端截图平均差 **11.1 / 11.5**（阈值 60，两轮分别 14:19 与 15:07；B 屏上就是 A 的设置页），T4-5 的笔迹也从 B 的手指点回到了 A 的悬浮层（15:07 那轮 3058 个变化像素）。原先写在这里的"qemu SLIRP 把两端都报成 `10.0.2.15` ⇒ `s-s` 不可路由、一帧没到"**是错的**：那条结论来自当时那轮的 `video_stall detail=rttMs_5_path_srflx-_srflx`，而事后证明它是坑㉑那个空判据（直读不存在的 `framesPerSecond`）产出的假告警，不是"帧没到"的读数——**用假告警去否证一条通路，得出了完全相反的判断，并且挂了两天**。口径：否证一条通路只能拿**正面读数**（截图对上了、`framesReceived` 区间相减 >0），"某告警在响"永远不构成"媒体没通"的证据。仍然成立的边界：① 本机的 `s-s` 是同一台宿主机两个 qemu 之间的选路，它证明"管线与判据在工作"，不证明真 NAT/HyperOS 上的选路与带宽行为——媒体结论的最终真值仍在真机双端；② AC-6 锁屏保活（坑⑮）本机只验过"判据不再是空的"，没验过"锁屏真的不断连"。③ **这一整段媒体判定力依赖一个不在仓库里的东西**：本机 dev server 现在的 `TURN_HOST/TURN_PORT/TURN_STATIC_AUTH_SECRET` 是按用户点头从线上 `.env` 用命令替换注入到**进程环境**的（没写进任何文件、不进 git、值也不落在任何文档里）；重启那台 dev server 就会退回占位值，`gather_done` 立刻回到 `r=0`、ICE 重新走 `NEW→CHECKING→FAILED`，t4 媒体段随之退回 `SKIP_ENV`——**看到媒体段又变灰，先怀疑这件事，不要怀疑埋点坏了**。
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
- **本机 dev 信令服务器必须在跑**——`e2e.sh` 自己不起服务，而 emu flavor 把信令地址写死成 `ws://10.0.2.2:8080`（=笔记本本身）：

  ```bash
  cd server && S2S_DATA_FILE=/tmp/s2s-emu-run/pairings.json nohup node src/index.js >> /tmp/s2s-emu-run/server.log 2>&1 &
  curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8080/health   # 200 才算起来
  ```

  `S2S_DATA_FILE` 不能省：默认值是 `server/data/pairings.json`（AGENTS 规定测试绝不写它）。
  它没起来时 t4 的第一条断言就是 `FAIL A 生成配对码 (NONE)`，且失败截图上写着「连接断开，正在重试…」——
  这是**前置条件缺失**，不是产品 bug，别拿它去改客户端。（10-19 实测：把这台常在跑的 dev server 当成
  "上一轮被 Ctrl-C 的孤儿进程"清理掉，于是整轮 t4 白跑一次。）
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
- 采集链路自己也会产空证据（v0.2.57）→ t0 守卫：`scripts/repro-capture.sh` 的 `check` 必须同时有 `saw_srv`/`saw_dev` 两条 state 缺行断言（缺任一路日志必须 rc=2，删一条守卫 t0 即红，已实测回滚变红）。判据分两层且不许混：**传输**用 ssh/adb 哑铃探（能抓住"logcat 停在 waiting for device"这种进程在、文件不动的死法），**流量**只用文件字节增量看、静默期一律 WARN——把"没人操作"报成 FAIL 只会让人忽略体检。真机侧一律 `logcat -T 1`，不带 `-T` 会把整块 ring buffer 倒进采集文件，采集起点含混=坑⑮同一类。
- signaling_down 是恒不成立的服务器判据（v0.2.57）→ PiPLockScreenGuardTest L4c：`onState` 断线侧必须记 `signalingDownSinceMonoMs = SystemClock.elapsedRealtime()`、复活侧必须读一次并 `= 0L`，且复活上报必须走具名收口 `reportSignaling("signaling_up", …)`；`reportSignaling` 里必须拼 `downMs=` 且只在 `downStartMonoMs > 0L` 时拼（首连不许报假长度）。根因：三类 diag 都要求 `Presence.client.isConnected` 才发，`signaling_down` 按定义就是 socket 已断时触发 → 只能进本机 logcat。埋点写了不等于采得到。
  v0.2.59 把断口起点从 `currentTimeMillis` 改成单调钟：复活那一刻恰好是系统重新校时的时刻，wall-clock 相减会把"断了 4 分钟"算成负数或几十小时（坑⑥的设备时钟版本）。
- **"发出去了"不等于"到了"：乐观发送盲区**（v0.2.59，双模拟器实测）→ `sendDiag` 在 `isConnected` 为真时是乐观发送（帧写进 socket 缓冲就算成功，无 ack 不抛异常），所以"网络刚丢、TCP 还没判死"那段发的待机事件会静默消失——客户端 logcat 有 `net_down`、服务器 0 条、补报队列里也没有。锁在 `StandbyTelemetryTest` S7：断连被确认的第一刻必须调 `StandbyProbe.replaySuspects(this@PresenceService)`（挪到别处=每次退避重连都刷一遍），副本只能在 `Presence.client.isConnected` 为真时入环（否则与 `sendDiag` 自己的入队重复产两条补报），副本必须带事件时刻 `at`，环形缓存必须封顶。抽掉 `replaySuspects` 调用 S7 即红（已实测）。
- **"埋了"不等于"发得出去"：探针必须在它要测的动作之前武装**（v0.2.59，10-18 由 0 条日志反推）→ `wake_ok` 在所有日志里一条都没有，不是因为通路坏，而是因为 `PresenceService` 先 `updateWakeLock()` 再 `StandbyProbe.start()`：首次 acquire 的回报落在探针 `running=false` 上被丢弃，连带心跳里的 `w=` 恒 0（锁其实拿着=读数撒谎）。锁在 `StandbyTelemetryTest` S8：onCreate 与 `paired` 分支里 `StandbyProbe.start` 必须排在 `updateWakeLock` 之前、`unpaired` 分支里放锁必须排在 `stop` 之前，`updateWakeLock` 两条出口都要回报探针，探针翻转要真发事件。把顺序换回去 S8 即红（已实测），修后模拟器装上即出 `wake_ok`。
  同一轮顺带的教训：`AuditRound2WiringTest` 用 `onCreate` 起 60 行的窗口找 `updateWakeLock`，我往匿名 `onState` 监听器里加十几行就把它撑红了（合法代码增长弄破写死行数的锚点）。新增埋点一律收成具名方法，守卫按名字开窗口。
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
| RtpQualityWindow (5) | 累计计数换算窗口丢包/隐藏音频时长；首样本/计数重置/缺失统计不伪报 0 | 弱网与语音质量诊断 |
| PairCodeBook (9) | +4999/+5000ms；旧码作废；第 5/6 次尝试；TTL ±1ms；sweep；self_pair | §2 防爆破（repo 常量） |
| MessageBook (10) | 限速 999/1000ms；发件人隔离；text 500/501；voice 校验（bad id/时长）；list 时序+拷贝+过期；ack 只删自己；容量溢出丢最旧；forgetDevice 双向；sweep TTL±1ms；self/空收件人 | 离线留言（未发版） |
| turn (5) | 固定 HMAC 向量（openssl 交叉验证）；udp+tcp；username 过期秒 | §2.3 |
| InboxStore (6) | fromJson 语音字段保留/缺 id→null/kind 缺省；按 id 去重；received 排 mine+时序；conversation 合并时序；remove 只删收到的；回调次数+重复 addAll 不触发 | 离线留言（未发版） |

> 括号内为用例数（原基线 Kotlin 93；新增 ICE/媒体窗口 6 项后为 Kotlin 99 / node 24 / 黑盒 48）。
