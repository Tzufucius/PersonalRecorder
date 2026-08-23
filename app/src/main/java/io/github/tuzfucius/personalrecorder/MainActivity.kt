package io.github.tuzfucius.personalrecorder

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.tuzfucius.personalrecorder.sync.CloudSyncRuntime
import io.github.tuzfucius.personalrecorder.settings.CloudSyncSettingsState
import io.github.tuzfucius.personalrecorder.settings.CloudSyncSettingsStore
import io.github.tuzfucius.personalrecorder.background.BackgroundHealthWorker
import androidx.lifecycle.lifecycleScope
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import io.github.tuzfucius.personalrecorder.ui.PersonalRecorderApp
import io.github.tuzfucius.personalrecorder.ui.theme.PersonalRecorderTheme

class MainActivity : ComponentActivity() {
    private val openSettings = MutableStateFlow(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        (application as? PersonalRecorderApplication)?.refreshRecentTaskPolicy()
        openSettings.value = intent.getBooleanExtra(EXTRA_OPEN_DIAGNOSTICS, false)
        CloudSyncRuntime.configure(this)
        BackgroundHealthWorker.schedule(this)
        BackgroundHealthWorker.enqueueNow(this)
        lifecycleScope.launch {
            val state = CloudSyncSettingsStore(this@MainActivity).state.first()
            (state as? CloudSyncSettingsState.Ready)?.settings?.let { settings ->
                CloudSyncRuntime.scheduler(this@MainActivity).schedule(settings.frequency)
            }
        }
        handleIntent(intent)
        setContent {
            PersonalRecorderTheme {
                val showSettings = openSettings.asStateFlow().collectAsState(initial = false).value
                PersonalRecorderApp(openSettings = showSettings)
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent?) {
        super.onNewIntent(intent)
        intent ?: return
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: android.content.Intent) {
        if (intent.getBooleanExtra(EXTRA_OPEN_DIAGNOSTICS, false)) openSettings.value = true
        if (intent.getBooleanExtra(EXTRA_SYNC_NOW, false)) {
            CloudSyncRuntime.scheduler(this).enqueueNow()
        }
    }

    companion object {
        const val EXTRA_OPEN_DIAGNOSTICS = "open_diagnostics"
        const val EXTRA_SYNC_NOW = "sync_now"
    }
}
