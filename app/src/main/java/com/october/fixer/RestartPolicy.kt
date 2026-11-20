package com.october.fixer

/** Allows one automatic restart per [windowMs]; a failure that comes sooner must stop the VPN. */
internal class RestartPolicy(private val windowMs: Long = 60_000) {
    private var lastRestartAt: Long? = null

    /** True: restart now (and the window starts). False: the previous restart is too recent. */
    @Synchronized
    fun onFailure(nowMs: Long): Boolean {
        val last = lastRestartAt
        if (last != null && nowMs - last < windowMs) return false
        lastRestartAt = nowMs
        return true
    }
}
