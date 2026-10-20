package app.droidnux

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.droidnux.desktop.DesktopActivity
import app.droidnux.ui.AppViewModel
import app.droidnux.ui.HomeScreen
import app.droidnux.ui.InstallState
import app.droidnux.ui.WizardScreen
import app.droidnux.ui.theme.DroidNuxTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // A Linux app's shortcut on Android's home screen: straight to the desktop.
        if (intent.action == ACTION_LAUNCH) {
            intent.getStringExtra(EXTRA_APP)?.let { app ->
                startActivity(Intent(this, DesktopActivity::class.java).putExtra(DesktopActivity.EXTRA_LAUNCH, app))
            }
            finish()
            return
        }
        setContent {
            DroidNuxTheme {
                val viewModel: AppViewModel = viewModel()
                val state by viewModel.state.collectAsStateWithLifecycle()
                LifecycleResumeEffect(Unit) {
                    viewModel.refresh()
                    onPauseOrDispose { }
                }
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    // Nothing until the first checks tell which screen it is.
                    if (state.rootfs != InstallState.Checking) {
                        AnimatedContent(state.ready, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "screen") { ready ->
                            if (ready) HomeScreen(state, viewModel) else WizardScreen(state, viewModel)
                        }
                    }
                }
            }
        }
    }

    companion object {
        /** Intent action of the Linux apps' shortcuts, with [EXTRA_APP]. */
        const val ACTION_LAUNCH = "app.droidnux.action.LAUNCH_APP"
        /** String extra: the app's desktop id. */
        const val EXTRA_APP = "app.droidnux.extra.APP"
    }
}
