package com.soul2soul.app.signaling

/**
 * 配对二维码载荷的构造与解析（纯逻辑，JVM 可单测）。
 * 扫码免手输 6 位码：生成端把 pair.code 包成 soul2soul://pair?code=xxxxxx，
 * 解析端同时兼容裸 6 位数字（别的二维码生成器/截图圈出来的码）。
 * 只认我们自己这一种 scheme，陌生二维码一律拒绝，防止把任意 URL 当配对指令。
 */
object PairQr {
    private const val SCHEME = "soul2soul"
    private const val HOST = "pair"
    private const val KEY = "code"
    private val CODE_RE = Regex("^\\d{6}$")

    /** @return null = 码不合法（不是 6 位数字），不生成垃圾二维码 */
    fun build(code: String): String? {
        val trimmed = code.trim()
        if (!CODE_RE.matches(trimmed)) return null
        return "$SCHEME://$HOST?$KEY=$trimmed"
    }

    /**
     * @return 6 位配对码，或 null（不是配对载荷）。
     * 大小写、前后空格、参数顺序都容错；scheme/host 必须精确匹配。
     */
    fun parse(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val s = raw.trim()
        if (CODE_RE.matches(s)) return s
        val m = Regex("^soul2soul://pair\\?(.+)$", RegexOption.IGNORE_CASE).find(s)
            ?: return null
        for (pair in m.groupValues[1].split('&')) {
            val kv = pair.split('=', limit = 2)
            if (kv.size == 2 && kv[0].equals(KEY, ignoreCase = true)) {
                val code = kv[1].trim()
                if (CODE_RE.matches(code)) return code
            }
        }
        return null
    }
}
