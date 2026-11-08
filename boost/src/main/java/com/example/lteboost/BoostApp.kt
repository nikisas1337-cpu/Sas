package com.example.lteboost

import android.app.Application
import org.lsposed.hiddenapibypass.HiddenApiBypass

class BoostApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Дозволяє викликати внутрішні системні інтерфейси (ITelephony тощо) через рефлексію.
        HiddenApiBypass.addHiddenApiExemptions("L")
    }
}
