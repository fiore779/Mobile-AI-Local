package com.mobileailocal.spike

import android.app.Application
import android.os.Process
import android.util.Log

/** 全プロセスで onCreate が動く(:runner でも)。副作用が無いことの確認用にログだけ出す */
class SpikeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Log.i("SPIKE", "APP_ONCREATE process=${Application.getProcessName()} pid=${Process.myPid()}")
    }
}
