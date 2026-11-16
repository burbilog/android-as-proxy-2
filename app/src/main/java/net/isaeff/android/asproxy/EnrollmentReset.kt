package net.isaeff.android.asproxy

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Explicit local device reset, requested by the visible UI only.
 * Not remote revocation: the old server administrator must run
 * aap2ctl revoke ID separately.
 */
internal object EnrollmentReset {
    enum class Status { IDLE, RUNNING, DONE, FAILED }

    private val _status = MutableStateFlow(Status.IDLE)
    val status = _status.asStateFlow()

    fun begin() { _status.value = Status.RUNNING }
    fun succeeded() { _status.value = Status.DONE }
    fun failed() { _status.value = Status.FAILED }
    fun dismissResult() { _status.value = Status.IDLE }

    /**
     * Only call from the foreground service AFTER disabling desired_running
     * and terminating the live SSH session/SOCKS listener. Keep the old
     * profile in place until the Keystore identity is deleted, so a failed
     * reset can be retried via the same UI; auto-reconnect stays disabled.
     */
    fun clearLocalCredentials(context: Context) {
        EnrollmentPending.clear(context)
        AndroidKeystoreIdentity.deleteForUserReset(context)
        DirectBootTunnelProfile.disable(context)
    }
}
