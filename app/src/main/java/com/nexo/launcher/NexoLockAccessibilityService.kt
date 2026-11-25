package com.nexo.launcher

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.view.accessibility.AccessibilityEvent

/** Uses Android's standard screen-lock action, matching the power-button lock path. */
class NexoLockAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeService = this
    }

    override fun onDestroy() {
        if (activeService === this) activeService = null
        super.onDestroy()
    }

    companion object {
        @Volatile private var activeService: NexoLockAccessibilityService? = null

        fun lockScreen(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            activeService?.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) == true
    }
}
