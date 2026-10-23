package com.soul2soul.app.session

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FR-16「把内录状况说给两个人听」的接线锁。
 *
 * 这一族失效的形状全是**不崩、无日志、只是那一行停在上一句上**：状态生产者接错一根线，
 * 他看到"TA 正在听"而采集线程其实早就自杀了；横幅写入点漏收一处，她在画面已停的时候
 * 读到一句关于声音的假确认。JVM 跑不出这些，所以锁结构。
 */
class DeviceAudioStatusWiringTest {

    private val servicePath = "app/src/main/java/com/soul2soul/app/session/ScreenShareService.kt"
    private val sessionPath = "app/src/main/java/com/soul2soul/app/session/SessionActivity.kt"
    private val routerPath = "app/src/main/java/com/soul2soul/app/session/SignalRouter.kt"
    private val mainPath = "app/src/main/java/com/soul2soul/app/MainActivity.kt"
    private val tapPath = "app/src/main/java/com/soul2soul/app/webrtc/DeviceAudioTap.kt"
    private val clientPath = "app/src/main/java/com/soul2soul/app/webrtc/WebRtcClient.kt"

    private fun file(rel: String): List<String> {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val f = File(dir, rel)
            if (f.exists()) return f.readLines()
            dir = dir.parentFile
        }
        throw IllegalStateException("找不到 $rel（工作目录: ${File("").absolutePath}）")
    }

    private fun functionBody(lines: List<String>, anchor: String): List<String> {
        val hits = lines.indices.filter { lines[it].contains(anchor) }
        assertTrue("锚点应恰好一次，实际 ${hits.size}：$anchor", hits.size == 1)
        val header = hits[0]
        val pad = " ".repeat(lines[header].takeWhile { it == ' ' }.length)
        val nextFun = Regex("""^$pad(\w+\s+)*fun\s""")
        val end = ((header + 1)..lines.lastIndex).firstOrNull { i ->
            lines[i] == "${pad}}" || nextFun.containsMatchIn(lines[i])
        } ?: error("找不到 $anchor 的收尾（锚点缩进被改了？）")
        return lines.subList(header, end)
    }

    private fun countOf(lines: List<String>, needle: String): Int = lines.count { it.contains(needle) }

    /**
     * 快照只有一个生产者：`audStatusForUi` 只许在 [publishAudStatus] 里写，
     * 加上新会话那一次归零。别处再写一次＝第二处真值（AGENTS「两处真值」），
     * 现象是主页与推给她的 `k=aud` 说的是两件事。
     */
    @Test
    fun `本地状态快照只有一个写入点`() {
        val service = file(servicePath)
        // 排除 companion 里那句声明（`var audStatusForUi = …OFF`），它不是"写入现值"
        val writes = service.filter { it.contains("audStatusForUi =") && !it.contains("var audStatusForUi") }
        // publishAudStatus 一处 + 新会话复位一处
        assertTrue("audStatusForUi 的赋值应恰好 2 处，实际 ${writes.size}：$writes", writes.size == 2)
        val publisher = functionBody(service, "private fun publishAudStatus(")
        assertTrue("publishAudStatus 必须写快照", publisher.any { it.contains("audStatusForUi =") })
        val main = file(mainPath)
        assertTrue("MainActivity 只读快照，不许自己算状态",
            main.any { it.contains("ScreenShareService.audStatusForUi") } &&
                main.none { it.contains("audStatusForUi =") })
    }

    /**
     * start / stop / 重开失败三条路都必须发一次快照。
     * 少一条的现象很具体：那条路径之后主页那一行停在"正在听"上继续撒谎，而用户没有任何办法发现。
     */
    @Test
    fun `每条起停路径都发布一次快照`() {
        val service = file(servicePath)
        val start = functionBody(service, "private fun startAudTap(")
        val stop = functionBody(service, "private fun stopAudTap(")
        // 按**支**数，不按整段数量：整段计数会被 onStateEdge 那一行的 publishAudStatus() 混进来，
        // 删掉门没过那一条照样"够数"——这条断言第一版就是这么假绿的（10-22 实测）
        val gate = start.indexOfFirst { it.contains("audGateReason() != DeviceAudioPolicy.Reason.START") }
        // 注释里也写着"return false"（那一行正在解释为什么只 return 不重试），所以必须先滤掉注释行——
        // 不滤的话锚点会停在注释上，断言数到的是"注释到注释之间"，恒为 0（10-22 实测踩过）
        val codeAt = { i: Int -> !start[i].trim().startsWith("//") }
        val refuseReturn = ((gate + 1) until start.size).firstOrNull { start[it].contains("return false") && codeAt(it) }
            ?: error("找不到门没过那一支的 return false")
        assertTrue("门没过那一支必须自己发一次快照（否则主页那一行停在上一态，用户读到的是\"没反应\"）",
            start.subList(gate + 1, refuseReturn).count { it.contains("publishAudStatus()") } == 1)
        val startedAt = start.indexOfFirst { it.contains("val started = tap.start(reason)") }
        val startedReturn = start.indexOfFirst { it.contains("return started") }
        assertTrue("tap.start() 之后必须再发一次（它返回 false 是真实存在的一瞬，两条结果都得改口）",
            start.subList(startedAt, startedReturn).count { it.contains("publishAudStatus()") } == 1)
        assertTrue("stopAudTap 必须发（关了开关那一行得跟着改口）",
            stop.any { it.contains("publishAudStatus()") })
        // 重开授权失败那一条：没人调 start/stop，得自己发
        val branch = service.subList(
            service.indexOfFirst { it.contains("ACTION_RESTART_CAPTURE -> {") },
            service.indexOfFirst { it.contains("ACTION_TOGGLE_AUD -> {") },
        )
        val okGate = branch.indexOfFirst { it.contains("if (ok) {") }
        assertTrue("重开失败分支必须自己发一次快照：$branch",
            branch.subList(okGate, branch.size).count { it.contains("publishAudStatus()") } >= 1)
    }

    /** 采集线程是唯一知道有没有读到声的人：它必须有一条沿口通向宿主，而不是宿主去猜 */
    @Test
    fun `线程状态沿接到快照发布`() {
        val tap = file(tapPath)
        assertTrue("DeviceAudioTap 必须有 onStateEdge 出口",
            tap.any { it.contains("onStateEdge: (state: String) -> Unit") })
        // 沿上才叫：每帧一报＝把 ctl 有序队列当音频道用
        val loopEdge = tap.indexOfFirst { it.contains("if (nextState != state) {") }
        val plainAssign = tap.indexOfFirst { it.contains("state = DeviceAudioPolicy.stateOf(silentStreak)") }
        assertTrue("采集循环里的 state 必须只在翻转沿上报（loopEdge@$loopEdge）", loopEdge >= 0)
        assertTrue("不许退回每帧无条件赋值（那等于每 21ms 发一条 ctl）", plainAssign < 0)
        val service = file(servicePath)
        assertTrue("宿主的沿回调必须回到主线程再发布（SignalBus/ctl 都不是线程安全的）",
            service.any { it.contains("onStateEdge = { watchdog.post { publishAudStatus() } }") })
    }

    /**
     * 推给她的那条必须去重（沿触发）：`aud` 是开关级事件，一通里最多几次。
     * 抽掉签名比较＝每帧一条 ctl，笔迹交棒的 ack 会排在它后面。
     */
    @Test
    fun `推送按签名去重且走 ctl`() {
        val publisher = functionBody(file(servicePath), "private fun publishAudStatus(")
        assertTrue("必须先算签名", publisher.any { it.contains("val sig =") })
        val compare = publisher.indexOfFirst { it.contains("if (sig == audPublishedSig) return") }
        val send = publisher.indexOfFirst { it.contains("sendControl(") }
        assertTrue("去重判断必须在发送之前（compare@$compare send@$send）", compare in 0 until send)
        assertTrue("载荷必须经 DeviceAudioHandshake.stateJson（协议形只有一处定义）",
            publisher.any { it.contains("DeviceAudioHandshake.stateJson(") })
        assertTrue("本地那一行要靠 local.deviceAudioState 事件刷新",
            publisher.any { it.contains("local.deviceAudioState") })
    }

    /**
     * 观看端横幅只有一个写入点，且有两个触发时刻：
     * ① 收到 `k=aud`（新版共享端报的）② 连通那一刻（"对面版本太旧"永远没有消息来报）。
     * 少 ②＝那一格永远不显示；少 ①＝她的横幅停在上一通。
     */
    @Test
    fun `观看端横幅单一写入点两个触发时刻`() {
        val session = file(sessionPath)
        val body = functionBody(session, "private fun refreshAudBanner(")
        val writes = countOf(session, "tvAudState.visibility")
        assertTrue("tvAudState 的可见性只许在 refreshAudBanner 里写（一处显示一处隐藏=2），实际 $writes",
            writes == 2)
        assertTrue("refreshAudBanner 必须同时负责显示与隐藏",
            body.any { it.contains("View.GONE") } && body.any { it.contains("View.VISIBLE") })
        val refreshIdx = session.indexOfFirst { it.contains("private fun refreshAudBanner(") }
        val calls = session.indices.filter {
            session[it].contains("refreshAudBanner()") && it != refreshIdx
        }
        assertTrue("必须有两个触发点（收 k=aud + 连通那一刻），实际 ${calls.size}", calls.size == 2)
        // 判定不许在 Activity 里再抄一遍
        assertTrue("横幅判定只经 SignalRouter.deviceAudioBannerRes 一处",
            countOf(session, "deviceAudioBannerRes(") == 1)
    }

    /**
     * "对面版本太旧"只能由观看端自己判：旧共享端既不建 `aud` 通道也不发 `k=aud`，
     * 这边没有任何消息可报错。判据是通道有没有出现过，所以它必须把 isSharer 折进去
     * （共享端自己建的通道恒真，误用会得出"她版本太旧"的反向谎话）。
     */
    @Test
    fun `旧对端判据只认观看端见过的通道`() {
        val client = file(clientPath)
        val getter = client.first { it.contains("val deviceAudioChannelSeen") }
        assertTrue("deviceAudioChannelSeen 必须把 !isSharer 折进去：$getter",
            getter.contains("!isSharer") && getter.contains("audChannel != null"))
        val router = file(routerPath)
        assertTrue("横幅必须真用上 channelSeen（不用它=那一格永远显示不出来）",
            router.any { it.contains("live && !channelSeen") })
    }

    /** 路由侧的 on 只认 0/1：不许把缺失读成"TA 没开" */
    @Test
    fun `aud 路由对未知 on 值不表态`() {
        val router = file(routerPath)
        val case = router.subList(
            router.indexOfFirst { it.contains("\"aud\" ->") },
            router.indexOfFirst { it.contains("\"screenon\" ->") },
        )
        assertTrue("必须显式挡掉非 0/1：$case", case.any { it.contains("audOn != 0 && audOn != 1") })
        assertTrue("不认识的值一律 None，不许造一个 false 出来",
            case.any { it.contains("DataAction.None") })
    }
}
