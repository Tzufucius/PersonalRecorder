package io.github.tuzfucius.personalrecorder

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.tuzfucius.personalrecorder.ui.PersonalRecorderApp
import io.github.tuzfucius.personalrecorder.ui.theme.PersonalRecorderTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PersonalRecorderTheme {
                PersonalRecorderApp()
            }
        }
    }
}
