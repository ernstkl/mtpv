package io.github.ernstkl.mtpv

import android.app.Application
import io.github.ernstkl.mtpv.controller.ControllerRuntime
import io.github.ernstkl.mtpv.core.PdfCache
import io.github.ernstkl.mtpv.core.RendererHolder
import io.github.ernstkl.mtpv.secondary.SecondaryRuntime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Holds the one active role. The last role is restored at startup, so the app resumes without any taps. */
class MtpvApp : Application() {
    lateinit var prefs: Prefs
        private set
    lateinit var cache: PdfCache
        private set
    lateinit var renderers: RendererHolder
        private set

    var controller: ControllerRuntime? = null
        private set
    var secondary: SecondaryRuntime? = null
        private set

    /** MTPV is on screen (MainActivity started). */
    @Volatile var inFront = false

    private val _role = MutableStateFlow(Role.NONE)
    val role: StateFlow<Role> = _role

    /** Changes with every new runtime, so the UI picks up the new one even if the role stays. */
    private val _generation = MutableStateFlow(0)
    val generation: StateFlow<Int> = _generation

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        cache = PdfCache(this)
        renderers = RendererHolder(cache)
        setRole(prefs.role)
    }

    fun setRole(role: Role) {
        controller?.stop()
        controller = null
        secondary?.stop()
        secondary = null
        prefs.role = role
        when (role) {
            Role.CONTROLLER -> controller = ControllerRuntime(this).also { it.start() }
            Role.SECONDARY -> secondary = SecondaryRuntime(this).also { it.start() }
            Role.NONE -> Unit
        }
        _role.value = role
        _generation.value++
    }

    /**
     * Stops the controller, runs [replace] (main thread) to change its stored data, and starts a new
     * controller that reads it afresh. Returns the new controller.
     */
    fun restartController(replace: () -> Unit): ControllerRuntime? {
        if (_role.value != Role.CONTROLLER) return null
        controller?.stop()
        controller = null
        try {
            replace()
        } finally {
            controller = ControllerRuntime(this).also { it.start() }
            _generation.value++
        }
        return controller
    }
}
