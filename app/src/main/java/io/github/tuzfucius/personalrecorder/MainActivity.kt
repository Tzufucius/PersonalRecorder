package io.github.tuzfucius.personalrecorder

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.tuzfucius.personalrecorder.sync.CloudSyncRuntime
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
        CloudSyncRuntime.configure(this)
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
}
