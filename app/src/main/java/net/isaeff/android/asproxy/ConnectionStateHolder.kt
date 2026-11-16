package net.isaeff.android.asproxy

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// Define the possible connection states
enum class ConnectionState { DISCONNECTED, CONNECTING, RETRYING, CONNECTED, DISCONNECTING }

/**
 * Singleton object to hold and manage the global connection state.
 * This allows the Service and the UI to synchronize on the current state.
 */
object ConnectionStateHolder {
    // MutableStateFlow to hold the current state, initially DISCONNECTED
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    // Expose the state as an immutable StateFlow for observation
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    // Status text is non-sensitive and persisted by ConnectionIssueStore, not here.
    private val _problem = MutableStateFlow<String?>(null)
    val problem: StateFlow<String?> = _problem.asStateFlow()
    fun setProblem(value: String?) { _problem.value = value }

    // Traffic counters (bytes in, bytes out) since the current session started
    private val _trafficBytes = MutableStateFlow(0L to 0L)
    val trafficBytes: StateFlow<Pair<Long, Long>> = _trafficBytes.asStateFlow()

    /**
     * Updates the current connection state.
     * @param state The new state to set.
     */
    fun setState(state: ConnectionState) {
        _connectionState.value = state
    }

    /**
     * Updates current traffic counters (in/out) in bytes.
     */
    fun setTrafficBytes(rxBytes: Long, txBytes: Long) {
        _trafficBytes.value = rxBytes to txBytes
    }
}
