package dev.shockstruck.gemininano

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppIdentityInstrumentedTest {

    @Test
    fun applicationIdIsTheManagerId() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("dev.shockstruck.gemininano", appContext.packageName)
    }

    @Test
    fun mergedManifestDoesNotRequestInternet() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        @Suppress("DEPRECATION")
        val info = appContext.packageManager.getPackageInfo(
            appContext.packageName,
            android.content.pm.PackageManager.GET_PERMISSIONS,
        )
        assertFalse(
            info.requestedPermissions.orEmpty().contains(android.Manifest.permission.INTERNET),
        )
    }
}
