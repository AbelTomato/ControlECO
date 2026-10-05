package com.accbook.app

import android.app.Application
import com.accbook.app.capture.CaptureCenter
import com.accbook.app.data.db.AccBookDatabase
import com.accbook.app.data.db.DefaultData
import com.accbook.app.util.AppLog
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
        CaptureCenter.init(this)
        applicationScope.launch { DefaultData.seedIfNeeded(db) }
    }
}
