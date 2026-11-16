package net.isaeff.android.asproxy

import org.junit.Assert.assertEquals
import org.junit.Test

class SshFailurePolicyTest {
    private fun classify(
        name: String, message: String? = null, isJsch: Boolean = true,
        rejected: Boolean = false
    ) = SshFailurePolicy.classify(name, message, isJsch, rejected)

    @Test fun actualPinnedKeyRejectionUsesSlowRetryWithoutRePinning() {
        assertEquals(SshFailurePolicy.Action.RETRY_HOST_IDENTITY_HOURLY,
            classify("JSchException", "Unknown failure text", rejected = true))
        assertEquals(SshFailurePolicy.Action.RETRY_HOST_IDENTITY_HOURLY,
            classify("RuntimeException", "Something else", isJsch = false, rejected = true))
        assertEquals(SshFailurePolicy.Action.RETRY_NETWORK,
            classify("JSchException", "fingerprint in unrelated diagnostic"))
    }

    @Test fun authenticationHasSlowBoundedRetry() {
        assertEquals(SshFailurePolicy.Action.RETRY_AUTH_HOURLY,
            classify("JSchException", "Auth fail"))
        assertEquals(SshFailurePolicy.Action.RETRY_AUTH_HOURLY,
            classify("JSchException", "Auth fail for methods 'publickey'"))
        assertEquals(SshFailurePolicy.Action.RETRY_AUTH_HOURLY,
            classify("JSchAuthCancelException"))
        assertEquals(SshFailurePolicy.Action.RETRY_AUTH_HOURLY,
            classify("JSchException", "Too many authentication failures"))
        assertEquals(3_600_000L, SshFailurePolicy.HOURLY_RETRY_MS)
    }

    @Test fun transientLocalFailureRetriesSlowlyAndNetworkRetriesNormally() {
        assertEquals(SshFailurePolicy.Action.RETRY_NETWORK,
            classify("JSchException", "session is down"))
        assertEquals(SshFailurePolicy.Action.RETRY_LOCAL_HOURLY,
            classify("ProviderException", "Temporary keystore problem", isJsch = false))
    }
}
