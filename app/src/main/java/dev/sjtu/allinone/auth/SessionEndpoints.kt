package dev.sjtu.allinone.auth

/** Read cookies for the native API path, including path-scoped login sessions. */
internal fun sessionCookieUrl(source: String): String = when (source) {
    "canvas" -> "https://oc.sjtu.edu.cn/api/v1/courses"
    "school" -> "https://j.sjtu.edu.cn/app/stu/exam/score/list"
    else -> throw IllegalArgumentException("未知登录平台")
}
