package com.mobileailocal.domain

/** 追加画面の入力の検証(設計書 7.2)。純粋関数。 */
object CloudInputs {
    private val modelNameRegex = Regex("""[A-Za-z0-9._:/@-]+""")
    const val MAX_MODEL_NAME = 200
    const val MAX_MODELS = 10
    const val MAX_DISPLAY_NAME = 40
    const val NO_SCOPE = ""

    /** キーの前後の空白と改行を除く。 */
    fun cleanKey(raw: String): String = raw.trim()

    /** モデル名(手入力)。前後の空白を除いて検証し、通れば名前、だめなら null。 */
    fun cleanModelName(raw: String): String? {
        val s = raw.trim()
        if (s.isEmpty() || s.length > MAX_MODEL_NAME) return null
        return s.takeIf { modelNameRegex.matches(it) }
    }

    fun cleanDisplayName(raw: String): String? = raw.trim().takeIf { it.length in 1..MAX_DISPLAY_NAME }

    /** クリップボードの文字列が「キーらしい」か(4〜400 文字、空白を含まない)。 */
    fun looksLikeKey(s: String?): Boolean {
        val t = s?.trim() ?: return false
        return t.length in 4..400 && t.none { it.isWhitespace() }
    }

    sealed interface UrlCheck {
        data object Ok : UrlCheck
        data object Invalid : UrlCheck
        /** 平文の http:// は、LAN でも許可しない。 */
        data object HttpNotAllowed : UrlCheck
    }

    /** baseUrl の検証。https:// だけ許可する。ユーザー情報・クエリ・フラグメントを含むものは不可。 */
    fun checkBaseUrl(raw: String): UrlCheck {
        val u = raw.trim()
        val m = Regex("""^(https?)://([^/?#]+)(/[^?#]*)?$""", RegexOption.IGNORE_CASE).matchEntire(u) ?: return UrlCheck.Invalid
        val scheme = m.groupValues[1].lowercase()
        val authority = m.groupValues[2]
        if ('@' in authority) return UrlCheck.Invalid
        hostOf(authority) ?: return UrlCheck.Invalid
        return if (scheme == "https") UrlCheck.Ok else UrlCheck.HttpNotAllowed
    }

    /** baseUrl のホスト部分(同意の本文と、同意の記録の単位に使う)。読めなければ null。 */
    fun hostOfUrl(baseUrl: String): String? {
        val m = Regex("^https?://([^/?#]+)", RegexOption.IGNORE_CASE).find(baseUrl.trim()) ?: return null
        return hostOf(m.groupValues[1])?.lowercase()
    }

    /** 同意の記録の単位。カスタムは接続先ごと(ホスト単位)、それ以外は種別ごと(空)。 */
    fun consentScope(type: ProviderType, baseUrl: String): String =
        if (type == ProviderType.OpenAiCompatCustom) hostOfUrl(baseUrl).orEmpty() else NO_SCOPE

    /** アカウントストアへ同期してよいか(https のみ。クエリ・ユーザー情報を含むものは不可)。 */
    fun syncable(baseUrl: String): Boolean = checkBaseUrl(baseUrl.trim()) == UrlCheck.Ok

    private fun hostOf(authority: String): String? {
        if (authority.startsWith("[")) { // IPv6
            val end = authority.indexOf(']')
            return if (end > 0) authority.substring(1, end) else null
        }
        val h = authority.substringBefore(':')
        return h.takeIf { it.isNotEmpty() }
    }
}
