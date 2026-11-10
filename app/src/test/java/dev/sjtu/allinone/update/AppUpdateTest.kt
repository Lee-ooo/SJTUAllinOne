package dev.sjtu.allinone.update

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateTest {
    @Test
    fun updateRequiresNewerVersionOfSamePackageAndValidMetadata() {
        val packageName = "dev.sjtu.allinone.debug"
        val update = Json.decodeFromString(
            AppUpdate.serializer(),
            """{"versionCode":3,"versionName":"0.1.2","packageName":"$packageName","sha256":"${"a".repeat(64)}"}""",
        )
        assertTrue(update.isNewerThan(packageName, 2))
        assertFalse(update.isNewerThan(packageName, 3))
        assertFalse(update.isNewerThan(packageName, 4))
        assertFalse(update.isNewerThan("dev.sjtu.allinone", 2))
        assertFalse(update.copy(versionName = "../0.1.2").isNewerThan(packageName, 2))
        assertFalse(update.copy(sha256 = "invalid").isNewerThan(packageName, 2))
        assertEquals(
            "https://github.com/Lee-ooo/SJTUAllinOne/releases/download/v0.1.2/SJTUAllinOne-0.1.2.apk",
            update.downloadUrl,
        )
    }
}
