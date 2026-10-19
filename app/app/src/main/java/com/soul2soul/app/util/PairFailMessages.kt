package com.soul2soul.app.util

import com.soul2soul.app.R

/**
 * 服务器 `pair.failed` 的 reason → 文案。
 * 每个 reason 必须落到**不同**的文案：「码不对」和「TA 还绑着旧设备」的下一步完全相反，
 * 合成一句就等于没有下一步（未知 reason 才落 generic）。
 */
object PairFailMessages {

    /** 服务器会发出的 reason 全集，供 t0 守卫与服务器源码对账（服务器新增一个名字、这里没跟上就红）。 */
    val SERVER_REASONS = setOf(
        "code_invalid", "already_paired", "too_many_attempts", "too_fast",
        "unavailable", "self_pair", "server_storage",
    )

    fun messageRes(reason: String?): Int = when (reason) {
        "code_invalid" -> R.string.pair_fail_code_invalid
        "already_paired" -> R.string.pair_fail_already_paired
        "too_many_attempts" -> R.string.pair_fail_too_many_attempts
        "too_fast" -> R.string.pair_fail_too_fast
        "unavailable" -> R.string.pair_fail_unavailable
        "self_pair" -> R.string.pair_fail_self_pair
        "server_storage" -> R.string.pair_fail_server_storage
        else -> R.string.pair_failed
    }
}
