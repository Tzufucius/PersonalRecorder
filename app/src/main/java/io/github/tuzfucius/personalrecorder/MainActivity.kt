package io.github.tuzfucius.personalrecorder

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.tuzfucius.personalrecorder.sync.CloudSyncRuntime
import io.github.tuzfucius.personalrecorder.sync.AccessTokenProvider
import io.github.tuzfucius.personalrecorder.sync.CloudCredentialStore
import io.github.tuzfucius.personalrecorder.sync.GoogleDriveCloudSyncBackend
import io.github.tuzfucius.personalrecorder.sync.GoogleDriveFolderResolver
import io.github.tuzfucius.personalrecorder.sync.OkHttpGoogleDriveRestClient
import io.github.tuzfucius.personalrecorder.sync.SecureSecretStore
import io.github.tuzfucius.personalrecorder.sync.SharedPreferencesGoogleDriveFolderIdCache
import io.github.tuzfucius.personalrecorder.sync.GitHubOAuthRuntime
import io.github.tuzfucius.personalrecorder.settings.CloudSyncSettingsState
import io.github.tuzfucius.personalrecorder.settings.CloudSyncSettingsStore
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import io.github.tuzfucius.personalrecorder.ui.PersonalRecorderApp
import io.github.tuzfucius.personalrecorder.ui.theme.PersonalRecorderTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val secrets = SecureSecretStore(this)
        val driveClient = OkHttpGoogleDriveRestClient(
            tokenProvider = AccessTokenProvider {
                secrets.get(CloudCredentialStore.GOOGLE_ACCESS_TOKEN)
            }
        )
        val driveBackend = GoogleDriveCloudSyncBackend(
            restClient = driveClient,
            folderResolver = GoogleDriveFolderResolver(
                restClient = driveClient,
                cache = SharedPreferencesGoogleDriveFolderIdCache(this)
            )
        )
        CloudSyncRuntime.configure(this, listOf(driveBackend))
        lifecycleScope.launch {
            val state = CloudSyncSettingsStore(this@MainActivity).state.first()
            (state as? CloudSyncSettingsState.Ready)?.settings?.let { settings ->
                CloudSyncRuntime.scheduler(this@MainActivity).schedule(settings.frequency)
            }
        }
        setContent {
            PersonalRecorderTheme {
                PersonalRecorderApp()
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.data?.let { GitHubOAuthRuntime.consumeCallback(this, it) }
    }
}
