package io.github.ernstkl.mtpv.ui

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ernstkl.mtpv.controller.ControllerRuntime
import io.github.ernstkl.mtpv.core.DirectCode
import io.github.ernstkl.mtpv.net.DirectState
import io.github.ernstkl.mtpv.net.DirectWifi
import io.github.ernstkl.mtpv.secondary.SecondaryRuntime

/** Below Android 13 the system prompt names only location, so [LocationNote] says why first. */
private val explainFirst = Build.VERSION.SDK_INT < 33

/** Why a music app asks for location; [onContinue] then shows the system prompt, also on dismiss. */
@Composable
private fun LocationNote(onContinue: () -> Unit) {
    AlertDialog(
        onDismissRequest = onContinue,
        title = { Text("Location permission") },
        text = {
            Text(
                "Up to Android 12, Android allows the direct network between the tablets only with the " +
                    "location permission. MTPV never reads your location. Without it, a shared Wi-Fi still works.",
            )
        },
        confirmButton = { TextButton(onClick = onContinue) { Text("Continue") } },
    )
}

/**
 * Asks for the Wi-Fi Direct permission if needed, then runs [action] either way: without the
 * permission the direct network reports what is missing.
 */
@Composable
private fun withDirectPermission(action: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val current by rememberUpdatedState(action)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { current() }
    var explaining by remember { mutableStateOf(false) }
    if (explaining) LocationNote { explaining = false; launcher.launch(DirectWifi.permissions) }
    return {
        when {
            DirectWifi.hasPermission(context) -> current()
            explainFirst -> explaining = true
            else -> launcher.launch(DirectWifi.permissions)
        }
    }
}

/**
 * Asks once for the Wi-Fi Direct permission while [needed], without a tap: the direct network may
 * start by itself, and Android only asks while the app is on screen. [onAnswered] follows either way.
 */
@Composable
fun AskDirectPermission(needed: Boolean, onAnswered: () -> Unit) {
    val current by rememberUpdatedState(onAnswered)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { current() }
    var asked by rememberSaveable { mutableStateOf(false) }
    var explaining by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(needed) {
        if (needed && !asked) {
            asked = true
            if (explainFirst) explaining = true else launcher.launch(DirectWifi.permissions)
        }
    }
    if (explaining) LocationNote { explaining = false; launcher.launch(DirectWifi.permissions) }
}

/** Subtitle of Direct network in the controller's menu. */
fun directSubtitle(state: DirectState, code: String, auto: Boolean): String = when {
    state != DirectState.Off -> DirectCode.format(code)
    auto -> "Starts when needed"
    else -> "Off"
}

/** The controller's direct network: its code, whether it starts by itself, Start or Stop, New code. */
@Composable
fun DirectNetworkDialog(rt: ControllerRuntime, onDismiss: () -> Unit) {
    val state by rt.direct.state.collectAsStateWithLifecycle()
    val code by rt.directCode.collectAsStateWithLifecycle()
    val auto by rt.directAuto.collectAsStateWithLifecycle()
    val start = withDirectPermission(rt::startDirect)
    val on = state != DirectState.Off
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Direct network") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("For places without a shared Wi-Fi: the other tablets join this tablet's own network.")
                Text(DirectCode.format(code), style = MaterialTheme.typography.headlineMedium, fontFamily = FontFamily.Monospace)
                Text(
                    when (val s = state) {
                        DirectState.Off -> if (auto) "Off until needed" else "Off"
                        DirectState.Starting -> "Starting…"
                        is DirectState.Up -> "On · " + when (s.clients) {
                            0 -> "no tablet joined yet"
                            1 -> "1 tablet joined"
                            else -> "${s.clients} tablets joined"
                        }
                        is DirectState.Failed -> s.reason
                    },
                    style = MaterialTheme.typography.titleSmall,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Start by itself when needed", Modifier.weight(1f))
                    Switch(checked = auto, onCheckedChange = rt::setDirectAuto)
                }
                Text(
                    "It starts when a paired screen cannot be reached, or when Screens finds nothing. " +
                        "Paired secondaries get the code from here and join by themselves. " +
                        "A secondary never paired: ☰ → Join direct network… there, type this code, then pair it under Screens.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (on) {
                    Text(
                        "New code: secondaries reachable only through this network drop out until the new code is typed there.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = rt::newDirectCode) { Text("New code") }
                if (on) TextButton(onClick = rt::stopDirect) { Text("Stop") } else TextButton(onClick = start) { Text("Start") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** In a secondary's ☰ dialog: whether it is in a direct network, Join and Leave. */
@Composable
fun DirectJoinSection(rt: SecondaryRuntime) {
    val state by rt.direct.state.collectAsStateWithLifecycle()
    val code by rt.joinCode.collectAsStateWithLifecycle()
    var entering by remember { mutableStateOf(false) }
    // Here, not in the code dialog: that one closes before the permission is answered.
    var typed by remember { mutableStateOf<String?>(null) }
    val join = withDirectPermission { typed?.let(rt::joinDirect) }
    Spacer(Modifier.height(12.dp))
    Text("Direct network", style = MaterialTheme.typography.labelLarge)
    val c = code
    if (c == null) {
        Text(
            "None yet. Once paired, the controller hands over its code, and this tablet joins its network " +
                "when there is no shared Wi-Fi. Never paired and no shared Wi-Fi: type the code shown on the controller.",
        )
        TextButton(onClick = { entering = true }) { Text("Join direct network…") }
    } else {
        Text("Code ${DirectCode.format(c)}")
        Text(
            when (val s = state) {
                DirectState.Off -> "Not needed now: joins when no controller reaches this tablet"
                DirectState.Starting -> "Looking for the controller's network…"
                is DirectState.Up -> "Joined" + (s.address?.let { ", address $it" } ?: "")
                is DirectState.Failed -> s.reason
            },
        )
        Row {
            TextButton(onClick = { entering = true }) { Text("Other code…") }
            TextButton(onClick = rt::leaveDirect) { Text("Leave") }
        }
    }
    if (entering) {
        JoinCodeDialog(onJoin = { typed = it; join() }) { entering = false }
    }
}

@Composable
private fun JoinCodeDialog(onJoin: (String) -> Unit, onDismiss: () -> Unit) {
    var digits by remember { mutableStateOf("") }
    val code = DirectCode.normalize(digits)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Join direct network") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Type the 16-digit code the controller shows under Direct network in its menu.")
                OutlinedTextField(
                    value = digits,
                    onValueChange = { v -> digits = v.filter { it in '0'..'9' }.take(DirectCode.DIGITS) },
                    label = { Text("Code") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    visualTransformation = GroupsOfFour,
                    textStyle = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    code?.let(onJoin)
                    onDismiss()
                },
                enabled = code != null,
            ) { Text("Join") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Shows the digits typed in groups of four, as the controller shows the code. */
private object GroupsOfFour : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val n = text.length
        return TransformedText(AnnotatedString(DirectCode.format(text.text)), object : OffsetMapping {
            override fun originalToTransformed(offset: Int) = offset + (offset - 1).coerceAtLeast(0) / 4
            override fun transformedToOriginal(offset: Int) = (offset - offset / 5).coerceIn(0, n)
        })
    }
}
