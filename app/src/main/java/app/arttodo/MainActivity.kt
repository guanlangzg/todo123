package app.arttodo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.arttodo.nav.ArtTodoNavHost
import app.arttodo.ui.common.PaperBackground
import app.arttodo.ui.theme.Studio
import app.arttodo.ui.theme.StudioTheme

/**
 * The single activity. Navigation ownership lives in `app.arttodo.nav`; this class only sets up the
 * window and hands off to the graph (工程布局与版本锁定.md §3).
 *
 * `android:configChanges` is deliberately not used: the app is expected to survive a real
 * recreation, and the active-session ownership lives in the Application, not here. Edge-to-edge is
 * on because the design consumes the status/navigation/cutout insets itself (设计系统 section 4.2)
 * rather than letting the system reserve a strip the illustrations would not fill.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            StudioTheme {
                AppFrame {
                    ArtTodoNavHost()
                }
            }
        }
    }
}

/** The page canvas: cream paper plus the faint grain, under everything the graph draws. */
@Composable
private fun AppFrame(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Studio.colors.canvas)) {
        PaperBackground(Modifier.fillMaxSize())
        content()
    }
}
