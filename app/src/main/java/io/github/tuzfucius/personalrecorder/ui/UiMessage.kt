package io.github.tuzfucius.personalrecorder.ui

import android.content.Context
import androidx.annotation.StringRes

/** A user-facing message that can be resolved in the current locale at render time. */
sealed interface UiMessage {
    data class Resource(
        @StringRes val id: Int,
        val args: List<Any> = emptyList(),
    ) : UiMessage

    data class Raw(val text: String) : UiMessage
}

fun UiMessage.resolve(context: Context): String = when (this) {
    is UiMessage.Resource -> context.getString(id, *args.toTypedArray())
    is UiMessage.Raw -> text
}

/** Localizes known persisted sync messages while preserving unknown technical details. */
fun localizedSyncError(context: Context, text: String): String {
    val message = text.lowercase()
    return when {
        "尚未连接" in text || "not configured" in message || "not connected" in message ->
            context.getString(io.github.tuzfucius.personalrecorder.R.string.sync_error_not_configured)
        "token" in message || "身份验证" in text || "authentication" in message ->
            context.getString(io.github.tuzfucius.personalrecorder.R.string.sync_error_authentication)
        "权限" in text || "authorization" in message || "无权" in text ->
            context.getString(io.github.tuzfucius.personalrecorder.R.string.sync_error_authorization)
        "冲突" in text || "conflict" in message ->
            context.getString(io.github.tuzfucius.personalrecorder.R.string.sync_error_remote_conflict)
        "归档" in text || "archive" in message ->
            context.getString(io.github.tuzfucius.personalrecorder.R.string.sync_error_invalid_archive)
        "频繁" in text || "rate" in message ->
            context.getString(io.github.tuzfucius.personalrecorder.R.string.sync_error_rate_limited)
        "服务" in text || "service" in message ->
            context.getString(io.github.tuzfucius.personalrecorder.R.string.sync_error_service_unavailable)
        "连接" in text || "network" in message ->
            context.getString(io.github.tuzfucius.personalrecorder.R.string.sync_error_network)
        else -> text
    }
}
