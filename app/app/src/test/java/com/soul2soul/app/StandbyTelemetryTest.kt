package com.soul2soul.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 待机遥测的源码锁（v0.2.59）。
 *
 * 这一版的全部意义是"米15 那台接不了 adb 的手机，一轮复现就能自证待机死因"，
 * 而它成立的前提是**探针真的随配对起、随解绑/销毁停**：
 * 漏 stop 就是解绑后仍在后台刷事件（隐私线 + 电量），漏 start 就是又回到待机零采样。
 * 下面每条都是这个对称性的一种表达。回滚任何一条，用例必须变红。
 */
class StandbyTelemetryTest {

    private fun source(rel: String): List<String> {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val f = File(dir, rel)
            if (f.exists()) return f.readLines()
            dir = dir.parentFile
        }
        throw IllegalStateException("找不到 $rel（工作目录: ${File("").absolutePath}）")
    }

    private fun probe() = source("app/src/main/java/com/soul2soul/app/util/StandbyProbe.kt")
    private fun presence() = source("app/src/main/java/com/soul2soul/app/session/PresenceService.kt")
    private fun webrtc() = source("app/src/main/java/com/soul2soul/app/webrtc/WebRtcClient.kt")
    private fun selfCheck() = source("app/src/main/java/com/soul2soul/app/util/SelfCheck.kt")
    private fun backlog() = source("app/src/main/java/com/soul2soul/app/util/DiagBacklog.kt")

    /** 锚点起往后取 size 行；锚点找不到直接红（"文件改了但断言落在别处"的假绿不允许存在） */
    private fun window(lines: List<String>, anchor: String, size: Int): List<String> {
        val i = lines.indexOfFirst { it.contains(anchor) }
        assertTrue("找不到锚点 $anchor", i >= 0)
        return lines.drop(i).take(size)
    }

    /** 两个名字锚点之间的片段——按名字找，不靠行数窗口（文件增长时窗口会溢出到别人的方法体） */
    private fun between(lines: List<String>, from: String, to: String): List<String> {
        val a = lines.indexOfFirst { it.contains(from) }
        val b = lines.indexOfFirst { it.contains(to) }
        assertTrue("找不到起点锚点 $from", a >= 0)
        assertTrue("找不到终点锚点 $to（在 $from 之后）", b > a)
        return lines.subList(a, b)
    }

    /** 只留代码行：注释里提到某个符号正是为了解释"为什么不能用它"，不该被扫描判成违规 */
    private fun code(lines: List<String>): List<String> = lines.filterNot {
        val t = it.trim()
        t.startsWith("//") || t.startsWith("/*") || t.startsWith("*")
    }

    // S1：探针内部的注册/注销严格对偶——漏一条 unregister 就是解绑后仍在收系统广播
    @Test
    fun `every probe registration has its matching unregistration in stop`() {
        val body = between(probe(), "fun stop(ctx: Context)", "fun isRunning()")
        assertEquals(
            "start 里注册了几个 receiver，stop 里就必须注销几个",
            probe().count { it.contains("ContextCompat.registerReceiver(") },
            body.count { it.contains("unregisterReceiver(") },
        )
        assertEquals(
            "默认网络回调必须注销，否则探针停了还在刷 net_down",
            probe().count { it.contains("registerDefaultNetworkCallback(") },
            body.count { it.contains("unregisterNetworkCallback(") },
        )
        // 三个注册开关都要在 stop 里复位，否则第二次 start 会跳过重注册却仍标记已注册
        listOf("screenReceiverRegistered", "dozeReceiverRegistered", "netCallbackRegistered")
            .forEach { assertTrue("stop 必须复位 $it", body.any { l -> l.contains("$it = false") }) }
        assertTrue("stop 必须撤掉心跳轮询", body.any { it.contains("handler.removeCallbacksAndMessages(null)") })
    }

    // S2：hideX 必穷举 restoreX——start/stop 在 PresenceService 的四个出入口都要对上
    @Test
    fun `probe starts on pair and stops on both unpair and destroy`() {
        val created = between(presence(), "override fun onCreate()", "override fun onStartCommand")
        assertTrue("配对状态下必须拉起待机探针", created.any { it.contains("StandbyProbe.start(this)") })
        assertTrue(
            "探针只能在已配对时启动：未配对时 diag.* 会被服务器丢弃，白刷电",
            created.any { it.contains("if (Prefs.paired(this)) com.soul2soul.app.util.StandbyProbe.start(this)") },
        )
        val destroyed = window(presence(), "override fun onDestroy()", 14)
        assertTrue("服务销毁必须停探针", destroyed.any { it.contains("StandbyProbe.stop(this)") })
        val unpaired = between(presence(), "\"unpaired\" -> {", "\"pair.recall_ask\" -> {")
        assertTrue("解绑必须停探针（无呼叫需求就不该再有待机遥测）", unpaired.any { it.contains("StandbyProbe.stop(this)") })
        val paired = between(presence(), "\"paired\" -> {", "\"unpaired\" -> {")
        assertTrue("配对成功当场就要拉起探针，不能等下一次服务创建", paired.any { it.contains("StandbyProbe.start(this)") })
    }

    // S3：会话结束那条必须真的带上选路与投影，否则服务器两列继续恒 `_`
    @Test
    fun `diag session carries path and proj instead of printing underscores`() {
        val put = window(selfCheck(), "fun reportSession(", 40)
        assertTrue("SelfCheck 必须把 path 写进 diag.session", put.any { it.contains("put(\"path\"") })
        assertTrue("SelfCheck 必须把 proj 写进 diag.session", put.any { it.contains("put(\"proj\"") })
        val end = window(webrtc(), "private fun reportSessionEnd(", 30)
        assertTrue(
            "会话结束必须传真实选路（取不到就留默认 -，不许编）",
            end.any { it.contains("path = if (lastSelectedPath ==") },
        )
        assertTrue(
            "选路必须来自锁存值而不是散会后那条 tick（那一刻 pairs 已经没了，读出来只能是被磨平的 o-o）",
            end.any { it.contains("DiagDetail.pathCode(lastSelectedPath)") },
        )
        assertTrue("会话结束必须传投影状态", end.any { it.contains("proj = projectionState") })
        assertTrue(
            "隐私停止时只能改状态、不能再发一条事件（同一件事两个真值来源）",
            window(webrtc(), "override fun onStop() {", 6).none { it.contains("reportEvent(") },
        )
    }

    // S4：真断（FAILED）必须与闪断分得开，且取证在恢复策略决定之前
    @Test
    fun `ice failed is reported before the recovery policy decides`() {
        // 窗口终点就是那句决策：上报若被挪到决策之后，它就不在窗口里 ⇒ 红（"谁把会话关掉的"又读不出来了）
        val branch = between(
            webrtc(),
            "PeerConnection.IceConnectionState.FAILED -> {",
            "IceRecoveryPolicy.onFailed(",
        )
        assertTrue("FAILED 必须自报（此前只打 logcat，米15 等于没报）",
            branch.any { it.contains("\"ice_failed\"") })
        assertTrue("必须带上是否已连通与重连次数，否则分不清首连失败与中途真断",
            branch.any { it.contains("established=") } && branch.any { it.contains("restarts=") })
        assertTrue("必须带上当时选路，relay 与 P2P 的真断是两个根因",
            branch.any { it.contains("path=") })
    }

    // S5：qos_tick 只允许有一个发射点，且站点必须是"10s 节奏 或 连上后的第一条"两态——
    //     发在 2s 轮询里就是 5 倍日志量；但只按 10s 出，短会话（她那种连上几秒就散）一条都读不到
    @Test
    fun `qos_tick is emitted only from the ten second diagnostic site`() {
        val lines = webrtc()
        val ticks = lines.filter { it.contains("\"qos_tick\"") }
        assertEquals("qos_tick 只能有一个发射点", 1, ticks.size)
        val i = lines.indexOfFirst { it.contains("\"qos_tick\"") }
        val gate = lines.take(i).indexOfLast { it.contains("if (established) {") }
        assertTrue("发射点必须在 established 门内（未连通的读数没有意义）", gate in 0 until i)
        assertTrue("established 门必须紧贴发射点（隔远了就不是这一层的门）", i - gate <= 3)
        val after = lines.subList(i, lines.size).take(20)
        assertTrue("同一站点必须插一条异常码（例行那条会把卡那一下盖掉）",
            after.any { it.contains("QosAnomaly.pick(") })
    }

    // S5a（v0.2.62 t4 实测后新增）：0 帧只允许有一个真值来源 = video_stall 的连续窗口。
    //     `b=fps0` 是单窗口判据：静态画面本来就 0 帧，双端每 10s 各一条 ⇒ 一分钟 12 条把 P0
    //     补报配额灌满（真异常反而被挤掉），而且它和 video_stall 指同一件事、证据更弱。
    @Test
    fun `zero fps is judged exactly once_and that place is the stall streak`() {
        val anomaly = source("app/src/main/java/com/soul2soul/app/webrtc/QosAnomaly.kt")
        assertTrue(
            "QosAnomaly 不许再出现 fps0 码（回滚即红）：0 帧归 video_stall 管",
            code(anomaly).none { it.contains("\"b=fps0\"") },
        )
        val lines = webrtc()
        val stall = lines.indexOfFirst { it.contains("viewerZeroFpsStreak++") }
        assertTrue("观看端必须保留连续窗口判据（撤掉 fps0 之后它是唯一出口）", stall >= 0)
        assertTrue(
            "streak 必须有「本端停帧」与「没读数」两个前置分支，否则又造出假告警",
            lines.take(stall).takeLast(16).any { it.contains("if (localRenderPaused)") } &&
                lines.take(stall).takeLast(16).any { it.contains("inboundVideoFps < 0.0") },
        )
    }

    // S5b（v0.2.62）：提前出来的那一条只当背景——它没有完整窗口，既不能驱动异常判据
    //（差分判据没有上一条可比），也不能推进 video_stall 的连续计数（会把告警提前一格）。
    @Test
    fun `the early qos_tick does not drive anomaly or stall counters`() {
        val lines = webrtc()
        val due = lines.indexOfFirst { it.contains("val diagDue =") }
        val first = lines.indexOfFirst { it.contains("val firstTick =") }
        val site = lines.indexOfFirst { it.contains("if (diagDue || firstTick) {") }
        assertTrue("站点必须先算 diagDue 再算 firstTick 再进门", due in 0 until first && first < site)
        assertTrue(
            "firstTick 必须同时要求 established 且本会话还没出过 tick（否则每 2s 补一条）",
            lines[first].contains("established && !qosTickEmitted"),
        )
        assertTrue(
            "异常判据必须挡掉「提前那条」：它全部是相对上一条的差分，prevQos 还不存在时没有基线",
            lines.any { it.contains("prevQos?.let { p ->") } &&
                lines.any { it.contains("QosAnomaly.pick(prev = p, cur = sample)") },
        )
        assertTrue(
            "video_stall 连续计数必须留在 10s 节奏上",
            lines.any { it.contains("if (diagDue && !isSharer && established && !remoteScreenPaused)") },
        )
    }

    // S5c（v0.2.62）：选路是闩锁而不是"最近一轮的原文"——断会后 stats 里没有 succeeded 的 pair，
    // 无脑覆盖会让 diag.session 的 path 在唯一需要它的短会话里退化成 u-u。
    @Test
    fun `selected path is latched and never overwritten by a missing reading`() {
        val lines = webrtc()
        val assign = lines.indexOfFirst { it.contains("lastSelectedPath = selectedPath") }
        assertTrue("必须有赋值点", assign >= 0)
        assertTrue(
            "赋值必须被「真的有选中对」的条件包住（unknown / ?->? 都不许覆盖闩锁）",
            lines.take(assign).takeLast(4).any { it.contains("if (selectedPath.contains(\"->\")") },
        )
        assertTrue(
            "diag.session 的 path 必须读闩锁值，并且从没读到过时留 \"-\"（不许拿 u-u 冒充知道）",
            lines.any { it.contains("path = if (lastSelectedPath == \"unknown\")") },
        )
    }

    // S6：心跳与补报路径绝不自己发起连接（D9 锁的待机版）
    @Test
    fun `standby telemetry never opens its own socket`() {
        listOf(probe() to "StandbyProbe", backlog() to "DiagBacklog", selfCheck() to "SelfCheck")
            .forEach { (lines, name) ->
                // 只查代码行：注释里出现 `.connect(` 恰恰是在解释为什么不能用它（SessionEndDiagTest 同口径）
                assertTrue(
                    "$name 不得出现 .connect(（自建连接会绕过 hello 鉴权并把 i/o error 洗白成没上报）",
                    code(lines).none { it.contains(".connect(") },
                )
            }
        assertTrue("心跳必须走 reportEvent 复用线上已认识的 diag.event",
            window(probe(), "private fun emit(event: String, detail: String)", 8)
                .any { it.contains("SelfCheck.reportEvent(") })
        assertTrue("心跳必须按 shouldHeartbeat 门控（会话中或未配对都不发）",
            probe().any { it.contains("StandbyPolicy.shouldHeartbeat(") })
        assertTrue("上一轮死因只能在信令连通时读（ socket 没通发了也发不出去）",
            window(presence(), "if (connected) {", 8).any { it.contains("StandbyProbe.reportPreviousExit") })
    }

    // S7：乐观发送盲区（v0.2.59 双模拟器实测补的洞）——net_down 这类事件在"socket 还没被判死"时
    // 会静默消失，唯一的救赎是断连确认那一刻把副本补进队列。这条锁的是"补报的补报"，
    // 抽掉它就回到"客户端 logcat 有、服务器 0 条"的假绿。
    @Test
    fun `death window is replayed exactly once per outage`() {
        val down = between(presence(), "if (signalingDownSinceMonoMs == 0L) {", "先建立总线订阅")
        assertTrue("断连确认的第一刻必须重放死亡窗口（挪到别处=每次退避重连都刷一遍）",
            down.any { it.contains("StandbyProbe.replaySuspects(this@PresenceService)") })
        assertTrue("断连起点必须用单调钟记录，wall-clock 相减会被复活时的时钟同步打穿",
            down.any { it.contains("signalingDownSinceMonoMs = SystemClock.elapsedRealtime()") })
        val report = between(presence(), "private fun reportSignaling(", "/** 会话期/待机期都算不出")
        assertTrue("downMs 必须由 elapsedRealtime 相减得出",
            report.any { it.contains("SystemClock.elapsedRealtime() - downStartMonoMs") })
        assertTrue("downMs 不许退回 currentTimeMillis 相减（AGENTS：设备时钟不可信）",
            code(report).none { it.contains("System.currentTimeMillis() - downStart") })

        // 副本只收"当时以为连着"的：isConnected 为假的那些 sendDiag 已经入过队，再收就是两条补报
        val emit = between(probe(), "private fun emit(event: String, detail: String)", "fun replaySuspects")
        assertTrue("入环前必须确认 socket 仍处于已连接判定（否则与 sendDiag 的入队重复）",
            emit.any { it.contains("Presence.client.isConnected") })
        assertTrue("副本必须带事件时刻 at（否则补出去全是复活那一毫秒，因果顺序就没了）",
            emit.any { it.contains("body.put(\"at\"") })
        assertTrue("环形缓存必须封顶，断连风暴不许无界吃内存",
            emit.any { it.contains("while (suspects.size > SUSPECT_MAX)") })
        val body = window(probe(), "fun replaySuspects(ctx: Context)", 14)
        assertTrue("重放必须按窗口过滤（几分钟前的正常心跳不是死亡现场）",
            body.any { it.contains("StandbyPolicy.shouldReplay(") })
        assertTrue("重放后必须清空，同一批副本不许补两次",
            body.any { it.contains("suspects.clear()") })
        assertTrue("stop 也要清空（解绑后的环里不许留着上一次的读数）",
            between(probe(), "fun stop(ctx: Context)", "fun isRunning()").any { it.contains("suspects.clear()") })

        assertTrue("补报入队不许覆盖调用方已带的事件时刻",
            backlog().any { it.contains("if (!copy.has(\"at\"))") })
    }

    // S8：保活锁探针必须"先武装、后拿锁"。10-18 之前的顺序是反的，结果 `wake_ok` 在所有日志里
    // 一条都没有——不是通路坏，是首次 acquire 落在 running=false 上，结构上永远发不出去。
    // 而"待机时锁到底拿到没有"正是待机断连要查的第一件事，这条不许再被顺序吃回来。
    @Test
    fun `wake lock probe is armed before the lock is taken`() {
        fun indexOf(lines: List<String>, anchor: String, from: List<String>, label: String): Int {
            val i = from.indexOfFirst { it.contains(anchor) }
            assertTrue("$label 里找不到锚点 $anchor", i >= 0)
            return i + lines.indexOf(from.first())
        }
        val created = between(presence(), "override fun onCreate()", "override fun onStartCommand")
        assertTrue("onCreate：探针必须在首次 updateWakeLock 之前起（否则首个 wake_ok 永久发不出）",
            indexOf(presence(), "StandbyProbe.start(this)", created, "onCreate") <
                indexOf(presence(), "updateWakeLock(Prefs.paired(this))", created, "onCreate"))
        val paired = between(presence(), "\"paired\" -> {", "\"unpaired\" -> {")
        assertTrue("配对当场：先起探针再拿锁",
            indexOf(presence(), "StandbyProbe.start(this)", paired, "paired") <
                indexOf(presence(), "updateWakeLock(true)", paired, "paired"))
        val unpaired = between(presence(), "\"unpaired\" -> {", "\"pair.recall_ask\" -> {")
        assertTrue("解绑：先放锁再停探针（停完再释放就没人报 wake_off）",
            indexOf(presence(), "updateWakeLock(false)", unpaired, "unpaired") <
                indexOf(presence(), "StandbyProbe.stop(this)", unpaired, "unpaired"))
        // 回报点本身也不许被删：acquire 成/败两条路都要喂给探针
        val updater = window(presence(), "private fun updateWakeLock(", 18)
        assertTrue("updateWakeLock 必须把结果回报给探针",
            updater.count { it.contains("StandbyProbe.onWakeLock(") } >= 2)
        assertTrue("翻转必须真的发事件，且失败优先于去重（异常每次都报）",
            between(probe(), "fun onWakeLock(", "private fun isIdle(")
                .any { it.contains("emit(if (held) \"wake_ok\" else \"wake_off\"") })
    }

    // S9：候选计数的第二个盲区（10-19 本机双模拟器 t4 实测：整轮 0 条 `gather_done`）。
    // ICE 快速 FAILED 这条路**走不到** onIceGatheringChange(COMPLETE)，而"TURN 一个中继都没拿到"
    // 恰恰只在死掉的那次才有意义——只监听 COMPLETE 等于在最需要它的场景里闭嘴，
    // 与 S8 那个 `wake_ok` 顺序 bug 同属"白埋"。收尾必须补一条 inc=1 的，且两次时机共用一个去重出口。
    @Test
    fun `gather tally is still reported when gathering never completes`() {
        val lines = webrtc()
        val sites = lines.filter { it.contains("reportGatherTally(incomplete =") }
        assertEquals("收集完成与会话收尾两个时机各一处，不许再多（同一会话两条=两个真值来源）", 2, sites.size)
        assertTrue("收集正常完成那条必须报成 incomplete=false（否则 inc 失去区分意义）",
            sites.any { it.contains("incomplete = false") })
        // 补发点只能在收尾这条路上：close() → reportSessionEnd() 是全仓库唯一的终态出口（D1 锁）
        val ending = between(lines, "private fun reportSessionEnd(", "SelfCheck.reportSession(")
        val guard = ending.indexOfFirst { it.contains("if (connectStartedAtMs <= 0L) return") }
        val cut = ending.indexOfFirst { it.contains("reportGatherTally(incomplete = true)") }
        assertTrue("收尾必须补发 incomplete=true 那条，否则快速 FAILED 的会话永远没有 relay 计数", cut >= 0)
        assertTrue(
            "补发必须在\"没发起过连接就 return\"之后：没真开始过就报全 0，会被读成\"TURN 分配失败\"",
            guard in 0 until cut,
        )
        val helper = window(lines, "private fun reportGatherTally(", 6)
        assertTrue("两次时机必须共用同一个去重标记（同一会话至多一条）",
            helper.any { it.contains("if (gatherReported) return") })
    }
}
