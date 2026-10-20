package io.github.ultradsu

import android.app.Application
import io.github.ultradsu.diagnostics.DebugLog
import io.github.ultradsu.data.SlotStore

class UltraDsuApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DebugLog.initialize(this)
        runCatching { DebugLog.rememberSlots(SlotStore(this).read()) }
    }
}
