package tech.torlando.reticulumkt

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import tech.torlando.reticulumkt.ui.navigation.AppNavigation
import tech.torlando.reticulumkt.ui.screens.DarkModeOption
import tech.torlando.reticulumkt.ui.theme.ReticulumTheme
import tech.torlando.reticulumkt.viewmodel.ReticulumViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val viewModel: ReticulumViewModel = viewModel()
            val theme by viewModel.theme.collectAsState()
            val darkModeOption by viewModel.darkMode.collectAsState()

            val isDarkTheme = when (darkModeOption) {
                DarkModeOption.SYSTEM -> isSystemInDarkTheme()
                DarkModeOption.LIGHT -> false
                DarkModeOption.DARK -> true
            }

            ReticulumTheme(
                darkTheme = isDarkTheme,
                selectedTheme = theme
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavigation(viewModel = viewModel)
                }
            }
        }
    }
}
