package com.tenderxiamu.eyecaretv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!ConfigStore(context).enabled) return
        runCatching { EyeCareService.start(context) }
            .onFailure { Log.w("BootReceiver", "开机启动服务失败", it) }
    }
}
