package com.videokeeper.mobile

import android.app.NotificationManager
import android.content.pm.ActivityInfo
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
        assertTrue(packageInfo.requestedPermissions.orEmpty().contains("android.permission.POST_NOTIFICATIONS"))
        assertTrue(packageInfo.requestedPermissions.orEmpty().contains("android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"))
        val activity = context.packageManager.getActivityInfo(
            android.content.ComponentName(context, MainActivity::class.java),
            0
        )
        assertTrue(activity.launchMode == ActivityInfo.LAUNCH_SINGLE_TOP)
        val service = context.packageManager.getServiceInfo(
            android.content.ComponentName(context, MediaProcessingService::class.java),
            android.content.pm.PackageManager.GET_META_DATA
        )
        assertNotNull(service)
        if (Build.VERSION.SDK_INT >= 35) {
            assertTrue(service.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING != 0)
        } else if (Build.VERSION.SDK_INT >= 29) {
            assertTrue(service.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC != 0)
        }
    }

    @Test fun longOperationsHaveProgressNotificationChannel() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        NotificationHelper.createChannels(context)
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = context.getSystemService(NotificationManager::class.java)
            val channel = manager.getNotificationChannel(NotificationHelper.PROCESSING_CHANNEL_ID)
                ?: error("processing notification channel was not created")
            assertFalse(channel.shouldVibrate())
            assertTrue(channel.sound == null)
        }
    }
}
