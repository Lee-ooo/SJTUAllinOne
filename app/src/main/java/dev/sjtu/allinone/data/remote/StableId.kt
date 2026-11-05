package dev.sjtu.allinone.data.remote

import java.security.MessageDigest

fun stableId(vararg parts: Any?): String {
    val digest = MessageDigest.getInstance("SHA-256")
    parts.forEach {
        digest.update((it?.toString() ?: "<null>").toByteArray())
        digest.update(0)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
