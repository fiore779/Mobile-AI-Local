package com.mobileailocal.domain

import com.mobileailocal.data.RemoteFile
import com.mobileailocal.data.RemoteModel

enum class Fit { Comfortable, Tight }

data class Recommendation(val model: RemoteModel, val file: RemoteFile, val fit: Fit)

/** 端末の目安サイズに合うモデルの選び方。固定のモデル名リストは持たない。 */
object ModelRecommender {
    const val COMFORT_RATIO = 0.70
    const val MIN_USEFUL_RATIO = 0.25
    const val MAX_ITEMS = 10
    const val MAX_CANDIDATES_FOR_TREE = 20

    private const val MB = 1024.0 * 1024.0
    private val paramsRegex = Regex("""(\d+(?:\.\d+)?)\s*[bB]""")
    private val eParamsRegex = Regex("""(?<![A-Za-z])E(\d+)[bB]""")
    private val quantRegex = Regex("""^I?Q(\d)""")

    /** 量子化が Q/IQ で始まり、続く最初の数字が 3〜6 か。 */
    private fun quantAllowed(quant: String): Boolean {
        val d = quantRegex.find(quant)?.groupValues?.get(1)?.toInt() ?: return false
        return d in 3..6
    }

    /** 1リポジトリのファイルから推奨ファイルを選ぶ。無ければ null。 */
    fun pickFile(files: List<RemoteFile>, budgetMb: Long): Pair<RemoteFile, Fit>? {
        val limit = budgetMb * MB
        val fit = files.filter {
            val n = it.fileName.lowercase()
            "mmproj" !in n && "imatrix" !in n && quantAllowed(it.quant) &&
                it.sizeBytes > 0 && it.sizeBytes <= limit
        }
        if (fit.isEmpty()) return null
        val comfortable = fit.filter { it.sizeBytes <= limit * COMFORT_RATIO }
        // 最大サイズ、同サイズなら量子化名が小さいほう
        fun best(list: List<RemoteFile>) =
            list.sortedWith(compareByDescending<RemoteFile> { it.sizeBytes }.thenBy { it.quant }).first()
        return if (comfortable.isNotEmpty()) best(comfortable) to Fit.Comfortable else best(fit) to Fit.Tight
    }

    /** リポジトリ名の "7B" "0.6B" "E4B" などから推定したパラメータ数(十億)。読めなければ null。 */
    fun paramsBillionOf(repoId: String): Double? {
        val name = repoId.substringAfterLast('/')
        eParamsRegex.find(name)?.let { return it.groupValues[1].toDouble() }
        return paramsRegex.find(name)?.groupValues?.get(1)?.toDoubleOrNull()
    }

    /** ツリー取得の前に除外してよいか(最小の量子化でも入らないもの)。読めなければ false。 */
    fun clearlyTooBig(repoId: String, budgetMb: Long): Boolean {
        val p = paramsBillionOf(repoId) ?: return false
        return p * 0.2 * 1024 > budgetMb
    }

    /** 人気順の候補から、表示する最大 MAX_ITEMS 件を決める。小さすぎるものは後ろへ回す。 */
    fun rank(picked: List<Recommendation>, budgetMb: Long): List<Recommendation> {
        val minBytes = budgetMb * MB * MIN_USEFUL_RATIO
        val (useful, small) = picked.partition { it.file.sizeBytes >= minBytes }
        return (useful + small).take(MAX_ITEMS)
    }
}
