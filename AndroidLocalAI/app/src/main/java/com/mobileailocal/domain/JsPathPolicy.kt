package com.mobileailocal.domain

/** fs API のパスの字句検査(ファイルには触れない)。設計書 5.3 の 1。 */
object JsPathPolicy {
    /**
     * 許可なら正規化した相対パス("a/b.txt"。区切りは "/")を返す。root を指す場合は ""(allowEmpty のときだけ)。
     * 拒否なら JsFsException("EPERM" / "EINVAL" / "ENAMETOOLONG")。
     */
    fun normalize(raw: Any?, allowEmpty: Boolean): String {
        if (raw !is String) throw JsFsException("EINVAL", "path must be a string")
        if (raw.isEmpty()) {
            if (allowEmpty) return ""
            throw JsFsException("EINVAL", "path is empty")
        }
        if (raw.length > JsLimits.MAX_PATH_CHARS) throw JsFsException("ENAMETOOLONG", "path too long")
        if (raw.startsWith("/")) throw JsFsException("EPERM", "absolute paths are not allowed")
        for (c in raw) {
            if (c == '\\' || c == ':' || c.code < 0x20 || c.code == 0x7F) {
                throw JsFsException("EINVAL", "invalid character in path")
            }
        }
        val parts = raw.split('/')
        if (parts.any { it == ".." }) throw JsFsException("EPERM", "'..' is not allowed")
        val kept = parts.filter { it.isNotEmpty() && it != "." }
        if (kept.isEmpty()) {
            if (allowEmpty) return ""
            throw JsFsException("EINVAL", "path is empty")
        }
        if (kept.size > JsLimits.MAX_DEPTH) throw JsFsException("ENAMETOOLONG", "path too deep")
        if (kept.any { it.length > JsLimits.MAX_NAME_CHARS }) throw JsFsException("ENAMETOOLONG", "name too long")
        return kept.joinToString("/")
    }
}
