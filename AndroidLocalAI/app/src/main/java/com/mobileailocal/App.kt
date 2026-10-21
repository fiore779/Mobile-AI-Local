package com.mobileailocal

import android.app.Application
import androidx.room.Room
import com.mobileailocal.data.AppDatabase
import com.mobileailocal.data.SettingsRepository
import com.mobileailocal.domain.FakeEngine
import com.mobileailocal.domain.InferenceEngine

/** 依存の置き場。DI ライブラリは入れず、ここで手動で組み立てる。 */
class App : Application() {
    lateinit var db: AppDatabase; private set
    lateinit var settings: SettingsRepository; private set
    lateinit var engine: InferenceEngine; private set

    override fun onCreate() {
        super.onCreate()
        db = Room.databaseBuilder(this, AppDatabase::class.java, "app.db").build()
        settings = SettingsRepository(this)
        engine = FakeEngine() // M0 で LlamaCppEngine に差し替える
    }
}
