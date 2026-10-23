package com.mobileailocal.data

import java.io.InputStream
import java.security.MessageDigest

/** HTTP の失敗(画面側で文字列リソースに置き換えて表示する)。 */
class HttpStatusException(val code: Int) : Exception("HTTP $code")

/** ダウンロードの続きの扱い。 */
enum class ResumeAction {
    /** 最初から(.part を作り直す)。 */
    Fresh,
    /** .part の続きから書き足す。 */
    Resume,
    /** .part が既に全体なので、ダウンロードせずに完成扱いにする。 */
    Complete,
}

/**
 * サーバーの応答コードから続きの扱いを決める。null は失敗(HTTP エラー)。
 * @param partLength 既にある .part の長さ(無ければ 0)。0 のときは Range を付けないので 200 だけが成功。
 * @param expectedSize 全体の期待サイズ(不明なら 0 以下)。416 のとき .part がこの長さなら完成扱い。
 */
fun resumeAction(partLength: Long, httpCode: Int, expectedSize: Long): ResumeAction? = when {
    httpCode == 206 && partLength > 0 -> ResumeAction.Resume
    httpCode in 200..299 -> ResumeAction.Fresh // 200: Range を無視されたので全体が来る
    httpCode == 416 && partLength > 0 ->
        if (expectedSize <= 0 || partLength == expectedSize) ResumeAction.Complete else ResumeAction.Fresh
    else -> null
}

/**
 * 入力から最大 maxBytes まで読み、digest に流し込む。読んだバイト数を返す。
 * chunk ごとに check() を呼ぶ(キャンセル判定用)。
 */
fun updateDigest(
    digest: MessageDigest, input: InputStream, maxBytes: Long = Long.MAX_VALUE, check: () -> Unit = {},
): Long {
    val b = ByteArray(256 * 1024)
    var remain = maxBytes
    var total = 0L
    while (remain > 0) {
        check()
        val n = input.read(b, 0, minOf(b.size.toLong(), remain).toInt())
        if (n < 0) break
        digest.update(b, 0, n); remain -= n; total += n
    }
    return total
}

/** 入力全体の SHA-256(16進)。 */
fun sha256Hex(input: InputStream): String {
    val d = MessageDigest.getInstance("SHA-256")
    updateDigest(d, input)
    return d.digest().toHex()
}

/** 照合の結果、導入せず .part を捨てるべきか。 */
fun shouldDiscard(expected: String?, actual: String): Boolean = !sha256Matches(expected, actual)

/** 進捗の全体サイズ。Content-Length が不明(負)なら 0(進捗は不確定表示になる)。 */
fun downloadTotal(start: Long, contentLength: Long): Long = if (contentLength < 0) 0L else start + contentLength
