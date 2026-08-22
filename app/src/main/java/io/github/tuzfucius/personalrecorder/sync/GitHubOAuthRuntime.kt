package io.github.tuzfucius.personalrecorder.sync

import android.content.Context
import android.content.Intent
import android.net.Uri
import io.github.tuzfucius.personalrecorder.BuildConfig

/** Starts the safe PKCE browser flow when a non-secret client ID is configured. */
object GitHubOAuthRuntime {
    @Volatile
    private var coordinator: GitHubOAuthDeepLinkCoordinator? = null

    fun start(context: Context): Result<Unit> = runCatching {
        val clientId = BuildConfig.GITHUB_CLIENT_ID.trim()
        require(clientId.isNotBlank()) { "未配置 GitHub OAuth client ID" }
        val deepLink = GitHubOAuthDeepLinkCoordinator(
            GitHubOAuthCoordinator(clientId),
            SecureSecretStore(context)
        )
        coordinator = deepLink
        val session = deepLink.startAuthorization()
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(session.authorizationUri)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    fun consumeCallback(context: Context, callbackUri: Uri): GitHubOAuthCallback {
        return runCatching {
            val deepLink = coordinator ?: GitHubOAuthDeepLinkCoordinator(
                GitHubOAuthCoordinator(BuildConfig.GITHUB_CLIENT_ID),
                SecureSecretStore(context)
            )
            deepLink.consumeCallback(callbackUri.toString()).first
        }.getOrElse { GitHubOAuthCallback.Invalid(it.message ?: "GitHub OAuth 回调不可用") }
    }
}
