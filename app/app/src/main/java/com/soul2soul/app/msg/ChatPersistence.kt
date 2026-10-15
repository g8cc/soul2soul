package com.soul2soul.app.msg

import org.json.JSONArray
import java.io.File

/**
 * 聊天记录本地落盘（app 私有目录，别人读不到；解绑即删）。
 * tmp+rename 原子写；renameTo 失败走 copy 回退——不能用 java.nio.file（API 26+，安卓7会崩）。
 */
object ChatPersistence {

    const val FILE_NAME = "chat_history.json"
    private const val MAX_KEEP = 500

    fun save(file: File, msgs: List<Msg>) {
        runCatching {
            val keep = msgs.sortedBy { it.ts }.takeLast(MAX_KEEP)
            val arr = JSONArray()
            for (m in keep) arr.put(m.toJson())
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(arr.toString())
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        }
    }

    /** 坏文件宁可当没有（返回空），不能崩在启动路径上 */
    fun load(file: File): List<Msg> = runCatching {
        if (!file.exists() || file.length() <= 0L) return emptyList()
        val arr = JSONArray(file.readText())
        (0 until arr.length()).mapNotNull { Msg.fromStoredJson(arr.optJSONObject(it)) }
    }.getOrDefault(emptyList())
}
