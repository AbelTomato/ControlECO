package com.controlexo.app

import android.app.Application
import com.controlexo.app.capture.CaptureCenter
import com.controlexo.app.data.db.AccBookDatabase
import com.controlexo.app.data.db.DefaultData
import com.controlexo.app.util.AppLog
import com.controlexo.app.util.Money
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AccBookApp : Application() {

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val db: AccBookDatabase by lazy { AccBookDatabase.get(this) }

    override fun onCreate() {
        super.onCreate()
        // 日志开关随构建类型：debug 全量输出（通知捕获排查用），release 静默
        AppLog.debugEnabled = BuildConfig.DEBUG
        // 货币符号在任何进程启动时就绪（服务/通知进程里格式化金额也要用对符号）
        Money.symbol = getSharedPreferences("settings", MODE_PRIVATE)
            .getString("currency_symbol", Money.DEFAULT_SYMBOL) ?: Money.DEFAULT_SYMBOL
        CaptureCenter.init(this)
        applicationScope.launch { DefaultData.seedIfNeeded(db) }
    }
}
