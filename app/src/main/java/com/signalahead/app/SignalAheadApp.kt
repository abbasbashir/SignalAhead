package com.signalahead.app

import android.app.Application
import com.signalahead.app.data.AppDatabase

class SignalAheadApp : Application() {
    val database by lazy { AppDatabase.create(this) }
    val settings by lazy { com.signalahead.app.tracking.UserSettings(this) }
}
