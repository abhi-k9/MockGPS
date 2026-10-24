package dev.burixon.mockgps

import android.app.Application

/** Reconcile retained observations before any receiver, tile, activity or service can act. */
class MockGpsApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ControlCenter.snapshot(this)
    }
}
