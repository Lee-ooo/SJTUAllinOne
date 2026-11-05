package dev.sjtu.allinone.domain

import dev.sjtu.allinone.data.local.SemesterEntity

fun widgetCacheStatus(profile: SemesterEntity?, now: Long): String = when {
    profile == null -> "请先设置学期"
    profile.importedAt == null -> "仅本地课表 · 未导入"
    now - profile.importedAt >= 30L * 24 * 60 * 60 * 1000 -> "课表缓存超过30天，请手动更新"
    else -> ""
}
