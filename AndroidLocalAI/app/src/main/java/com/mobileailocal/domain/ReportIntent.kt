package com.mobileailocal.domain

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import com.mobileailocal.BuildConfig
import com.mobileailocal.R

/** 報告メールの本文に使う固定文言(文字列リソースから取り出して渡す)。 */
data class ReportLabels(
    val intro: String,
    val responseHeading: String,
    val appVersionLine: String,
    val modelLine: String,
    val androidLine: String,
)

/** Context に依存しない本文の組み立て(単体テスト用)。 */
object ReportBody {
    /** 回答の最大文字数。メールアプリに渡す長さの上限として切る。 */
    const val MAX_ANSWER_CHARS = 2000

    /**
     * 本文は、報告する回答・アプリのバージョン・モデル名・Android のバージョンだけ(会話全体は含めない)。
     * 回答が長いときは先頭 MAX_ANSWER_CHARS 文字までにして末尾に … を付ける。
     */
    fun build(labels: ReportLabels, reportedText: String): String {
        val answer = if (reportedText.length > MAX_ANSWER_CHARS) {
            // サロゲートペアの途中で切らない
            var end = MAX_ANSWER_CHARS
            if (Character.isHighSurrogate(reportedText[end - 1])) end--
            reportedText.substring(0, end) + "…"
        } else reportedText
        return labels.intro + "\n\n" +
            labels.responseHeading + "\n" + answer + "\n\n" +
            labels.appVersionLine + "\n" + labels.modelLine + "\n" + labels.androidLine
    }
}

/** AI の回答の報告メール下書きを、端末のメールアプリで開く。送信するかは利用者が決める。 */
object ReportIntent {
    fun buildBody(context: Context, reportedText: String, modelName: String?): String =
        ReportBody.build(
            ReportLabels(
                intro = context.getString(R.string.report_body_intro),
                responseHeading = context.getString(R.string.report_body_response),
                appVersionLine = context.getString(R.string.report_body_app, BuildConfig.VERSION_NAME),
                modelLine = context.getString(R.string.report_body_model, modelName ?: "-"),
                androidLine = context.getString(R.string.report_body_android, Build.VERSION.RELEASE),
            ),
            reportedText,
        )

    fun launch(context: Context, reportedText: String, modelName: String?) {
        val subject = context.getString(R.string.report_subject)
        val body = buildBody(context, reportedText, modelName)
        // EXTRA_TEXT を無視するメールアプリがあるので、mailto: の URI にも件名と本文を入れる
        val uri = Uri.parse(
            "mailto:" + context.getString(R.string.report_email) +
                "?subject=" + Uri.encode(subject) + "&body=" + Uri.encode(body),
        )
        val intent = Intent(Intent.ACTION_SENDTO, uri).apply {
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, R.string.report_no_mail_app, Toast.LENGTH_LONG).show()
        }
    }
}
