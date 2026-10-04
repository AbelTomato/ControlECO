package com.accbook.app

import android.app.Application
import com.accbook.app.data.db.AccBookDatabase
import com.accbook.app.data.db.DefaultData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AccBookApp : Application() {

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val db: AccBookDatabase by lazy { AccBookDatabase.get(this) }

    override fun onCreate() {
        super.onCreate()
        applicationScope.launch { DefaultData.seedIfNeeded(db) }
    }
}
