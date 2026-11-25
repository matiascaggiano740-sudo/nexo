package com.nexo.launcher

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Runs first on the previously signed APK and again after installing the candidate over it. */
@RunWith(AndroidJUnit4::class)
class UpgradePersistenceTest {
    private val targetContext get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun seedUserDataBeforeUpgrade() {
        targetContext.getSharedPreferences("nexo_upgrade_e2e", 0).edit()
            .putString("user_layout_marker", "preserve-across-upgrade")
            .putInt("user_setting_marker", 42)
            .commit()
        assertEquals("preserve-across-upgrade", targetContext.getSharedPreferences("nexo_upgrade_e2e", 0).getString("user_layout_marker", null))
    }

    @Test fun updatedPackageKeepsUserDataAndHasNewVersion() {
        val prefs = targetContext.getSharedPreferences("nexo_upgrade_e2e", 0)
        assertEquals("User preferences must survive a signed in-place APK update", "preserve-across-upgrade", prefs.getString("user_layout_marker", null))
        assertEquals("Other persisted settings must survive too", 42, prefs.getInt("user_setting_marker", -1))
        val info = targetContext.packageManager.getPackageInfo(targetContext.packageName, 0)
        @Suppress("DEPRECATION")
        val versionCode = if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        assertTrue("The installed candidate must be newer than the historical APK", versionCode > 279L)
    }
}
