package com.controleco.app.util

import android.util.Log

/**
 * 统一日志出口。tag 固定为 ControlECO + 子标签，便于 Logcat 过滤；
 * 调试开关保留给后续自动记账链路（通知/无障碍采集需要大量排查日志）。
 */
object AppLog {
    private const val TAG = "ControlECO"

    @Volatile
    var debugEnabled: Boolean = true

    fun d(sub: String, msg: String) {
        if (debugEnabled) Log.d(TAG, "[$sub] $msg")
    }

    fun i(sub: String, msg: String) {
        Log.i(TAG, "[$sub] $msg")
    }

    fun w(sub: String, msg: String, tr: Throwable? = null) {
        Log.w(TAG, "[$sub] $msg", tr)
    }

    fun e(sub: String, msg: String, tr: Throwable? = null) {
        Log.e(TAG, "[$sub] $msg", tr)
    }
}
