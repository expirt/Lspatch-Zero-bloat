package org.lsposed.lspatch.ui.navigation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate

/**
 * Carries files chosen by the built-in file picker back to the screen that opened it.
 *
 * Avoids passing complex objects across serializable navigation back stack routes.
 */
object FileSelection {

    /** [requestedBy] is the identity of the caller (e.g. request token, or screen tag). */
    data class Selection(val requestedBy: String, val paths: List<String>)

    private val _pending = MutableStateFlow<Selection?>(null)
    val pending: StateFlow<Selection?> = _pending.asStateFlow()

    /** Called by BuiltInFilePicker when the user confirms their selection. */
    fun offer(requestedBy: String, paths: List<String>) {
        _pending.value = Selection(requestedBy, paths)
    }

    /** The selection [requestedBy] asked for, or null when the pending one is not theirs. */
    fun consume(requestedBy: String): List<String>? =
        _pending.getAndUpdate { if (it?.requestedBy == requestedBy) null else it }
            ?.takeIf { it.requestedBy == requestedBy }
            ?.paths
}
