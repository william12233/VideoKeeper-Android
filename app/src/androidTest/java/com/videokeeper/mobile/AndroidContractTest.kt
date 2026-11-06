package com.videokeeper.mobile

import android.content.pm.ServiceInfo
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidContractTest {
    @Test fun appIsOfflineAndDeclaresMediaProcessingService() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            android.content.pm.PackageManager.GET_PERMISSIONS or android.content.pm.PackageManager.GET_SERVICES
        )
        assertFalse(packageInfo.requestedPermissions.orEmpty().contains("android.permission.INTERNET"))
        val service = context.packageManager.getServiceInfo(
            android.content.ComponentName(context, MediaProcessingService::class.java),
            android.content.pm.PackageManager.GET_META_DATA
        )
        assertNotNull(service)
        if (Build.VERSION.SDK_INT >= 35) {
            assertTrue(service.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING != 0)
        }
    }
}
