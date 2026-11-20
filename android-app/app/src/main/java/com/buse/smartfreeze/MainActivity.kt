package com.buse.smartfreeze

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.buse.smartfreeze.ui.AppViewModelFactory
import com.buse.smartfreeze.ui.navigation.AppNavigation
import com.buse.smartfreeze.ui.theme.SmartFreezeTheme
import com.buse.smartfreeze.ui.theme.ThemeMode

class MainActivity : ComponentActivity() {

    private val destination = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        destination.value = intent?.getStringExtra(EXTRA_DESTINATION)
        val app = application as SmartFreezeApp
        val factory = AppViewModelFactory.create(app)
        setContent {
            // null until the settings row has been read once (avoids flashing onboarding to returning users)
            val settings by app.container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
            val mode = settings?.themeMode?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM
            SmartFreezeTheme(mode) {
                Surface(Modifier.fillMaxSize()) {
                    val s = settings
                    if (s == null) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    } else {
                        AppNavigation(factory, s.onboardingDone, destination.value) { destination.value = null }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        destination.value = intent.getStringExtra(EXTRA_DESTINATION)
    }

    companion object {
        const val EXTRA_DESTINATION = "destination"
    }
}
