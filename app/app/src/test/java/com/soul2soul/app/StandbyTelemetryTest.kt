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
        assertTrue("会话结束必须传真实选路（取不到就留默认 -，不许编）", end.any { it.contains("path = lastQos") })
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

    // S5：qos_tick 只允许从 10s 诊断站点发——发在 2s 轮询里就是 5 倍日志量，且读数窗口未闭合
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
        assertTrue(
            "异常码必须带上本端停帧与对方锁屏两个抑制条件，否则锁屏期间每 10s 造一条假故障",
            after.any { it.contains("framesBlockedByUs = localRenderPaused || !localScreenOn()") } &&
                after.any { it.contains("peerPaused = remoteScreenPaused") },
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
}
