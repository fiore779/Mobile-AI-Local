package com.mobileailocal.ui

import androidx.annotation.StringRes
import com.mobileailocal.R
import com.mobileailocal.domain.ApiError
import com.mobileailocal.domain.ApiErrorKind

/** 警告に付けるボタンの種類(設計書 6.3-3)。 */
enum class CloudErrorAction { ReEnterKey, OpenSettings, OpenProviderConsole, Retry, ReselectModel, SwitchToLocal }

data class CloudErrorUi(@StringRes val messageRes: Int, val actions: List<CloudErrorAction>)

/** エラーの種別から、文言とボタンを決める(純粋関数)。 */
fun cloudErrorUi(kind: ApiErrorKind): CloudErrorUi = when (kind) {
    ApiErrorKind.AuthInvalid -> CloudErrorUi(R.string.cloud_err_auth, listOf(CloudErrorAction.ReEnterKey))
    ApiErrorKind.PermissionDenied -> CloudErrorUi(R.string.cloud_err_permission, listOf(CloudErrorAction.OpenSettings))
    // 上限・課金系は再試行しても直らないので、再試行ボタンは出さない
    ApiErrorKind.BillingRequired -> CloudErrorUi(R.string.cloud_err_billing, billingActions)
    ApiErrorKind.SpendLimit -> CloudErrorUi(R.string.cloud_err_spend, billingActions)
    ApiErrorKind.QuotaExceeded -> CloudErrorUi(R.string.cloud_err_quota, billingActions)
    ApiErrorKind.RateLimited -> CloudErrorUi(R.string.cloud_err_rate, listOf(CloudErrorAction.Retry))
    ApiErrorKind.Overloaded -> CloudErrorUi(R.string.cloud_err_overloaded, listOf(CloudErrorAction.Retry))
    ApiErrorKind.ModelNotFound -> CloudErrorUi(R.string.cloud_err_model_not_found, listOf(CloudErrorAction.ReselectModel))
    ApiErrorKind.ContextTooLong -> CloudErrorUi(R.string.cloud_err_context, emptyList())
    ApiErrorKind.InvalidRequest -> CloudErrorUi(R.string.cloud_err_invalid, emptyList())
    ApiErrorKind.ContentBlocked -> CloudErrorUi(R.string.cloud_err_blocked, emptyList())
    ApiErrorKind.ServerError -> CloudErrorUi(R.string.cloud_err_server, listOf(CloudErrorAction.Retry))
    ApiErrorKind.Timeout -> CloudErrorUi(R.string.cloud_err_timeout, listOf(CloudErrorAction.Retry))
    ApiErrorKind.Network -> CloudErrorUi(R.string.cloud_err_network, listOf(CloudErrorAction.Retry, CloudErrorAction.SwitchToLocal))
    ApiErrorKind.StreamCut -> CloudErrorUi(R.string.cloud_err_stream_cut, listOf(CloudErrorAction.Retry))
    ApiErrorKind.Unknown -> CloudErrorUi(R.string.cloud_err_unknown, listOf(CloudErrorAction.Retry))
}

private val billingActions = listOf(CloudErrorAction.OpenSettings, CloudErrorAction.OpenProviderConsole)

/** 「詳細」に出す提供側の情報(原文)。何も無ければ null。コード・状態・メッセージ・解除日時・request id。 */
fun cloudErrorDetail(e: ApiError): String? {
    val lines = buildList {
        e.httpStatus?.let { add("HTTP $it") }
        e.providerCode?.let { add(it) }
        e.providerMessage?.let { add(it) }
        e.resetAt?.let { add("reset: $it") }
        e.requestId?.let { add("request_id: $it") }
    }
    return lines.takeIf { it.isNotEmpty() }?.joinToString("\n")
}
