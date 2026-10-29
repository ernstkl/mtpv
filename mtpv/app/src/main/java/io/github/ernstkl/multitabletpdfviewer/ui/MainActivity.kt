package io.github.ernstkl.multitabletpdfviewer.ui

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ernstkl.multitabletpdfviewer.MtpvApp
import io.github.ernstkl.multitabletpdfviewer.Prefs
import io.github.ernstkl.multitabletpdfviewer.Role

class MainActivity : ComponentActivity() {
    private val app get() = application as MtpvApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    val role by app.role.collectAsStateWithLifecycle()
                    val generation by app.generation.collectAsStateWithLifecycle()
                    // The role keeps running while another one is being chosen; back or Cancel keeps it.
                    var choosing by remember { mutableStateOf(false) }
                    if (role == Role.NONE || choosing) {
                        RoleChooser(app.prefs, current = role, onCancel = { choosing = false }) {
                            choosing = false
                            if (it != role) app.setRole(it)
                        }
                    } else {
                        key(generation) {
                            when (role) {
                                Role.CONTROLLER -> app.controller?.let { ControllerScreen(it) { choosing = true } }
                                Role.SECONDARY -> app.secondary?.let { SecondaryScreen(it) { choosing = true } }
                                Role.NONE -> Unit
                            }
                        }
                    }
                }
            }
        }
        if (savedInstanceState == null) handleOpenIntent(intent)
    }

    /** MTPV works only while on screen: the role resumes here and pauses in [onStop]. */
    override fun onStart() {
        super.onStart()
        app.inFront = true
    }

    override fun onStop() {
        app.inFront = false
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOpenIntent(intent)
    }

    /** A PDF opened with this app makes this device the controller and shows the PDF. */
    private fun handleOpenIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        if (app.role.value != Role.CONTROLLER) app.setRole(Role.CONTROLLER)
        app.controller?.openPdf(uri)
    }

    /** Bluetooth page-turn pedals send key events; the usual ones are mapped here, in both roles. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val action = when (keyCode) {
            in NEXT_KEYS -> "next"
            in PREV_KEYS -> "prev"
            else -> return super.onKeyDown(keyCode, event)
        }
        app.controller?.let { if (action == "next") it.next() else it.prev() }
        app.secondary?.sendInput(action)
        return true
    }

    companion object {
        private val NEXT_KEYS = setOf(
            KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_NEXT,
        )
        private val PREV_KEYS = setOf(
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS,
        )
    }
}

/** The first start, or Device role: [current] is the role still running, NONE on the first start. */
@Composable
private fun RoleChooser(prefs: Prefs, current: Role, onCancel: () -> Unit, onChoose: (Role) -> Unit) {
    var showHelp by remember { mutableStateOf(!prefs.helpShown) }
    BackHandler(enabled = current != Role.NONE, onBack = onCancel)
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("MTPV", style = MaterialTheme.typography.headlineLarge)
        Text("Multi-Tablet PDF Viewer")
        Spacer(Modifier.height(8.dp))
        Text("What should this tablet do? The app remembers the choice.")
        Spacer(Modifier.height(32.dp))
        Button(onClick = { onChoose(Role.CONTROLLER) }, Modifier.width(320.dp)) {
            Text("Controller: open PDFs and turn pages")
        }
        Text("Only one tablet? Choose Controller.", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = { onChoose(Role.SECONDARY) }, Modifier.width(320.dp)) {
            Text("Secondary: show one page")
        }
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = { showHelp = true }) { Text("Help") }
        if (current != Role.NONE) {
            TextButton(onClick = onCancel) {
                Text("Cancel: stay " + if (current == Role.CONTROLLER) "controller" else "secondary")
            }
        }
    }
    if (showHelp) {
        HelpDialog {
            showHelp = false
            prefs.helpShown = true
        }
    }
}
