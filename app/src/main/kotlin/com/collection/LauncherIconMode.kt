package com.collection

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

internal object LauncherIconMode {
    @Synchronized fun apply(context: Context, dynamic: Boolean) {
        val pm = context.packageManager
        val enabled = ComponentName(context, if (dynamic) "com.collection.DynamicLauncher" else "com.collection.StaticLauncher")
        val disabled = ComponentName(context, if (dynamic) "com.collection.StaticLauncher" else "com.collection.DynamicLauncher")
        if (pm.getComponentEnabledSetting(enabled) != PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
            pm.setComponentEnabledSetting(enabled, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
        if (pm.getComponentEnabledSetting(disabled) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED)
            pm.setComponentEnabledSetting(disabled, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
    }
}
