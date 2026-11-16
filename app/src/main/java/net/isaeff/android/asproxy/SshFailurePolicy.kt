package net.isaeff.android.asproxy

/**
 * Classify SSH failures without guessing host identity from exception text.
 * Host-key rejection comes directly from PinnedRepository.check() and never
 * triggers re-pinning, TOFU or an authentication attempt on the wrong server.
 *
 * JSch exposes ordinary server auth rejection mainly as a JSchException
 * message, so keep only a narrow list of known auth failures for hourly retry.
 * Unknown JSch failures keep their existing bounded network backoff.
 */
internal object SshFailurePolicy {
    enum class Action {
        RETRY_HOST_IDENTITY_HOURLY, RETRY_LOCAL_HOURLY,
        RETRY_AUTH_HOURLY, RETRY_NETWORK
    }

    const val HOURLY_RETRY_MS = 60 * 60 * 1000L

    fun classify(
        exceptionClassName: String, message: String?, isJsch: Boolean,
        pinnedHostKeyRejected: Boolean
    ): Action {
        if (pinnedHostKeyRejected) return Action.RETRY_HOST_IDENTITY_HOURLY
        if (!isJsch) return Action.RETRY_LOCAL_HOURLY

        val knownAuthRejection = exceptionClassName == "JSchAuthCancelException" ||
            message == "Auth fail" || message?.startsWith("Auth fail:") == true ||
            message == "Auth cancel" || message?.startsWith("Auth cancel:") == true ||
            message?.startsWith("Too many authentication failures") == true
        return if (knownAuthRejection) Action.RETRY_AUTH_HOURLY else Action.RETRY_NETWORK
    }
}
