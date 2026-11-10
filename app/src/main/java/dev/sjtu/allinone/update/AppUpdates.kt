package dev.sjtu.allinone.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

private const val RELEASES = "https://github.com/Lee-ooo/SJTUAllinOne/releases"
private const val MAX_APK_BYTES = 100L * 1024 * 1024
private val VERSION = Regex("[0-9]+\\.[0-9]+\\.[0-9]+")
private val SHA256 = Regex("[0-9a-fA-F]{64}")

@Serializable
internal data class AppUpdate(
    val versionCode: Long,
    val versionName: String,
    val packageName: String,
    val sha256: String,
    val notes: String = "",
) : java.io.Serializable {
    val downloadUrl: String
        get() = "$RELEASES/download/v$versionName/SJTUAllinOne-$versionName.apk"

    fun isNewerThan(installedPackage: String, installedVersion: Long): Boolean =
        packageName == installedPackage && versionCode > installedVersion &&
            VERSION.matches(versionName) && SHA256.matches(sha256)

}

internal object AppUpdates {
    private val client = OkHttpClient.Builder()
        .followSslRedirects(false)
        .callTimeout(3, TimeUnit.MINUTES)
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    @Suppress("DEPRECATION")
    private val signatureFlag = if (Build.VERSION.SDK_INT >= 28) {
        PackageManager.GET_SIGNING_CERTIFICATES
    } else {
        PackageManager.GET_SIGNATURES
    }

    @Suppress("DEPRECATION")
    suspend fun check(context: Context): AppUpdate? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$RELEASES/latest/download/update.json").build()
        val update = client.newCall(request).execute().use { response ->
            val body = response.body ?: throw IOException("缺少更新清单")
            if (!response.isSuccessful || body.source().request(32 * 1024L)) {
                throw IOException("无法读取更新清单")
            }
            json.decodeFromString(AppUpdate.serializer(), body.string())
        }
        val installed = context.packageManager.getPackageInfo(context.packageName, 0)
        update.takeIf {
            it.isNewerThan(context.packageName, PackageInfoCompat.getLongVersionCode(installed))
        }
    }

    @Suppress("DEPRECATION")
    suspend fun download(context: Context, update: AppUpdate): File = withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        val partial = File.createTempFile("update-", ".apk", context.cacheDir)
        try {
            val request = Request.Builder().url(update.downloadUrl).build()
            val digest = MessageDigest.getInstance("SHA-256")
            client.newCall(request).execute().use { response ->
                val body = response.body ?: throw IOException("下载文件为空")
                if (!response.isSuccessful) throw IOException("APK 下载失败")
                body.byteStream().use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            ensureActive()
                            val count = input.read(buffer)
                            if (count == -1) break
                            total += count
                            if (total > MAX_APK_BYTES) throw IOException("APK 超出大小限制")
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                        }
                    }
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            if (!hash.equals(update.sha256, ignoreCase = true)) throw IOException("APK 校验失败")
            val manager = context.packageManager
            val installed = manager.getPackageInfo(context.packageName, signatureFlag)
            val apk = manager.getPackageArchiveInfo(partial.path, signatureFlag)
            if (apk == null || apk.packageName != installed.packageName ||
                PackageInfoCompat.getLongVersionCode(apk) != update.versionCode ||
                update.versionCode <= PackageInfoCompat.getLongVersionCode(installed) ||
                signers(installed).isEmpty() || signers(apk) != signers(installed)
            ) throw IOException("APK 与当前应用的包名、版本或签名不匹配")
            ensureActive()
            val target = File(directory, "update-${update.versionCode}.apk")
            if (!partial.renameTo(target)) throw IOException("无法保存 APK")
            directory.listFiles()?.filter { it != target }?.forEach { it.delete() }
            target
        } finally {
            partial.delete()
        }
    }

    fun installIntent(context: Context, apk: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    @Suppress("DEPRECATION")
    private fun signers(info: PackageInfo) = if (Build.VERSION.SDK_INT >= 28) {
        info.signingInfo?.apkContentsSigners.orEmpty().toSet()
    } else {
        info.signatures.orEmpty().toSet()
    }
}
