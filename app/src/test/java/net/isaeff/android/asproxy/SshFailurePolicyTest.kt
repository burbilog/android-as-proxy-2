package net.isaeff.android.asproxy

import org.junit.Assert.assertEquals
import org.junit.Test

class SshFailurePolicyTest {
    @Test fun hostKeyMismatchIsNeverRetriedOrRePinned() {
        assertEquals(SshFailurePolicy.Action.FATAL_HOST_IDENTITY,
            SshFailurePolicy.classify("JSchChangedHostKeyException", null, true))
        assertEquals(SshFailurePolicy.Action.FATAL_HOST_IDENTITY,
            SshFailurePolicy.classify("JSchException", "HostKey has been changed", true))
        assertEquals(SshFailurePolicy.Action.FATAL_HOST_IDENTITY,
            SshFailurePolicy.classify("JSchException", "reject HostKey", true))
    }

    @Test fun authenticationHasSlowBoundedRetry() {
        assertEquals(SshFailurePolicy.Action.RETRY_AUTH_HOURLY,
            SshFailurePolicy.classify("JSchException", "Auth fail", true))
        assertEquals(SshFailurePolicy.Action.RETRY_AUTH_HOURLY,
            SshFailurePolicy.classify("JSchAuthCancelException", "", true))
        assertEquals(3_600_000L, SshFailurePolicy.AUTH_RETRY_MS)
    }

    @Test fun networkMayReconnectButLocalBugCannotLoopForever() {
        assertEquals(SshFailurePolicy.Action.RETRY_NETWORK,
            SshFailurePolicy.classify("JSchException", "session is down", true))
        assertEquals(SshFailurePolicy.Action.FATAL_LOCAL,
            SshFailurePolicy.classify("IllegalStateException", "Failed to load profile", false))
    }
}
