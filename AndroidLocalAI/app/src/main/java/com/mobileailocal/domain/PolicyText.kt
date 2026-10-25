package com.mobileailocal.domain

/** アプリ内に同梱するプライバシーポリシーの本文の扱い(純粋なロジック)。 */
object PolicyText {
    /** 空欄({{...}})のうち、連絡先メールアドレスに当たるもの(4 言語)。アプリの設定値で埋める。 */
    private val CONTACT_PLACEHOLDERS = listOf("{{contact email}}", "{{連絡先メールアドレス}}", "{{联系邮箱}}", "{{聯絡信箱}}")
    private val placeholderRegex = Regex("""\{\{[^{}]+\}\}""")

    /** 選んでいる言語に合うポリシーのファイル名(assets/privacy/ 配下)。 */
    fun fileName(language: String, country: String = "", script: String = ""): String = when (language.lowercase()) {
        "ja" -> "privacy-policy.ja.md"
        "zh" -> if (script.equals("Hant", true) || country.uppercase() in setOf("TW", "HK", "MO")) "privacy-policy.zh-TW.md"
        else "privacy-policy.zh-CN.md"
        else -> "privacy-policy.en.md"
    }

    /** 連絡先の空欄を contactEmail で埋める。 */
    fun fill(raw: String, contactEmail: String): String =
        CONTACT_PLACEHOLDERS.fold(raw) { acc, p -> acc.replace(p, contactEmail) }

    /** まだ埋まっていない空欄({{...}})。公開前に本文側で埋める必要があるものの確認用。 */
    fun unresolved(text: String): List<String> = placeholderRegex.findAll(text).map { it.value }.distinct().toList()
}
