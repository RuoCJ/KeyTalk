package com.keytalk.app

import android.Manifest
import android.content.Context
import android.content.pm.FeatureInfo
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ManifestPermissionTest {
    @Test
    fun mvpBRequestsOnlyNetworkAndCameraSensitiveDevicePermissions() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val permissions = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions
            .orEmpty()
            .toSet()

        assertTrue(permissions.contains(Manifest.permission.INTERNET))
        assertTrue(permissions.contains(Manifest.permission.CAMERA))
        assertFalse(permissions.contains(Manifest.permission.READ_CONTACTS))
        assertFalse(permissions.contains(Manifest.permission.READ_SMS))
        assertFalse(permissions.contains(Manifest.permission.ACCESS_FINE_LOCATION))
        assertFalse(permissions.contains(Manifest.permission.ACCESS_COARSE_LOCATION))
        assertFalse(permissions.contains(Manifest.permission.RECORD_AUDIO))
        assertFalse(permissions.contains(Manifest.permission.READ_EXTERNAL_STORAGE))
        assertFalse(permissions.contains(Manifest.permission.WRITE_EXTERNAL_STORAGE))
        assertFalse(permissions.contains(Manifest.permission.READ_MEDIA_IMAGES))
        assertFalse(permissions.contains(Manifest.permission.READ_MEDIA_VIDEO))
        assertFalse(permissions.contains(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))

        val cameraFeature = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_CONFIGURATIONS)
            .reqFeatures
            .orEmpty()
            .firstOrNull { it.name == PackageManager.FEATURE_CAMERA }

        assertTrue(cameraFeature != null)
        assertFalse(cameraFeature!!.flags and FeatureInfo.FLAG_REQUIRED != 0)
    }
}
