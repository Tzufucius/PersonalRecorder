package io.github.tuzfucius.personalrecorder.sync

import android.content.Context

/** GitHub PAT 的唯一凭证入口，实际密文由 Android Keystore 保护。 */
class CloudCredentialStore(context: Context) {
    private val secrets = SecureSecretStore(context)

    fun clearGithub() = secrets.remove(GITHUB_ACCESS_TOKEN)

    companion object {
        const val GITHUB_ACCESS_TOKEN = "github_access_token"
    }
}
