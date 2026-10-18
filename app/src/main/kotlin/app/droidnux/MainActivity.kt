package app.droidnux

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.droidnux.ui.DiagnosticsScreen
import app.droidnux.ui.theme.DroidNuxTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            DroidNuxTheme {
                DiagnosticsScreen()
            }
        }
    }
}
