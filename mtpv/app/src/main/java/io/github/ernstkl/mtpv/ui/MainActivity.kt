package io.github.ernstkl.mtpv.ui

import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
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
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ernstkl.mtpv.MtpvApp
import io.github.ernstkl.mtpv.Role

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
                    when (role) {
                        Role.NONE -> RoleChooser(app::setRole)
                        Role.CONTROLLER -> app.controller?.let { ControllerScreen(it) { app.setRole(Role.NONE) } }
                        Role.SECONDARY -> app.secondary?.let { SecondaryScreen(it) { app.setRole(Role.NONE) } }
                    }
                }
            }
        }
    }

    /** Bluetooth page-turn pedals send key events; the usual ones are mapped here. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val controller = app.controller ?: return super.onKeyDown(keyCode, event)
        when (keyCode) {
            in NEXT_KEYS -> controller.next()
            in PREV_KEYS -> controller.prev()
            else -> return super.onKeyDown(keyCode, event)
        }
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

@Composable
private fun RoleChooser(onChoose: (Role) -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("MTPV", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(8.dp))
        Text("What should this tablet do? The app remembers the choice.")
        Spacer(Modifier.height(32.dp))
        Button(onClick = { onChoose(Role.CONTROLLER) }, Modifier.width(320.dp)) {
            Text("Controller: open PDFs and turn pages")
        }
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = { onChoose(Role.SECONDARY) }, Modifier.width(320.dp)) {
            Text("Secondary: show one page")
        }
    }
}
