package io.github.tuzfucius.personalrecorder.sync

import android.content.Context

/** Clears provider credentials without deleting local archives or sync history. */
class CloudCredentialStore(context: Context) {
    private val secrets = SecureSecretStore(context)

    fun clearGithub() {
        secrets.remove(GITHUB_ACCESS_TOKEN)
        secrets.remove(GITHUB_REFRESH_TOKEN)
        secrets.remove(GITHUB_PENDING_STATE)
        secrets.remove(GITHUB_PENDING_VERIFIER)
    }

    fun clearGoogleDrive() {
        secrets.remove(GOOGLE_ACCESS_TOKEN)
        secrets.remove(GOOGLE_REFRESH_TOKEN)
        secrets.remove(GOOGLE_ROOT_FOLDER_ID)
    }

    companion object {
        const val GITHUB_ACCESS_TOKEN = "github_access_token"
        const val GITHUB_REFRESH_TOKEN = "github_refresh_token"
        const val GITHUB_PENDING_STATE = "github_pending_state"
        const val GITHUB_PENDING_VERIFIER = "github_pending_verifier"
        const val GOOGLE_ACCESS_TOKEN = "google_drive_access_token"
        const val GOOGLE_REFRESH_TOKEN = "google_drive_refresh_token"
        const val GOOGLE_ROOT_FOLDER_ID = "google_drive_root_folder_id"
    }
}
