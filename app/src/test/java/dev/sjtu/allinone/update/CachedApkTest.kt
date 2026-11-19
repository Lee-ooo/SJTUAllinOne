package dev.sjtu.allinone.update

import android.app.Application
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class CachedApkTest {
    @Test
    fun reusesOnlyTheVerifiedApkForTheAvailableVersion() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val packageManager = shadowOf(context.packageManager)
        val apk = File(context.cacheDir, "updates/update-2.apk")
        val contents = "test APK".toByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(contents)
            .joinToString("") { "%02x".format(it) }
        val update = AppUpdate(2, "0.1.2", context.packageName, hash)

        fun packageInfo(version: Long, signature: Signature) = PackageInfo().apply {
            packageName = context.packageName
            longVersionCode = version
            signingInfo = ReflectionHelpers.callConstructor(SigningInfo::class.java)
            shadowOf(signingInfo).setSignatures(arrayOf(signature))
        }

        val signer = Signature(byteArrayOf(1))
        packageManager.installPackage(packageInfo(1, signer))
        packageManager.setPackageArchiveInfo(apk.path, packageInfo(2, signer))

        assertNull(AppUpdates.cachedApk(context, update))
        apk.parentFile!!.mkdirs()
        apk.writeBytes(contents)
        assertEquals(apk, AppUpdates.cachedApk(context, update))
        assertNull(AppUpdates.cachedApk(context, update.copy(versionCode = 3)))

        packageManager.setPackageArchiveInfo(apk.path, packageInfo(2, Signature(byteArrayOf(2))))
        assertNull(AppUpdates.cachedApk(context, update))
        packageManager.setPackageArchiveInfo(apk.path, packageInfo(2, signer))
        apk.writeText("corrupted APK")
        assertNull(AppUpdates.cachedApk(context, update))
    }
}
