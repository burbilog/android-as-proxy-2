package net.isaeff.android.asproxy

/**
 * Classify SSH failures without guessing host identity from exception text.
 * Host-key rejection comes directly from PinnedRepository.check() and never
 * triggers re-pinning, TOFU or an authentication attempt on the wrong server.
 *
 * JSch exposes ordinary server auth rejection mainly as a JSchException
 * message, but a transport exception during userauth can produce exactly the
 * same message. Retry once normally before throttling consecutive auth failures.
 * Unknown JSch failures keep their existing bounded network backoff.
 */
internal object SshFailurePolicy {
    enum class Action {
        RETRY_HOST_IDENTITY_HOURLY, RETRY_LOCAL_HOURLY,
        RETRY_AUTH_HOURLY, RETRY_NETWORK
    }

    /** Per tunnel loop; any non-auth outcome breaks the consecutive streak. */
    class RetrySequence {
        private var previousAuthFailure = false

        fun next(classified: Action): Action {
            val auth = classified == Action.RETRY_AUTH_HOURLY
            val throttle = auth && previousAuthFailure
            previousAuthFailure = auth
            return if (auth && !throttle) Action.RETRY_NETWORK else classified
        }

        fun reset() {
            previousAuthFailure = false
        }
    }

    const val HOURLY_RETRY_MS = 60 * 60 * 1000L

    fun classify(
        exceptionClassName: String, message: String?, isJsch: Boolean,
        pinnedHostKeyRejected: Boolean
    ): Action {
        if (pinnedHostKeyRejected) return Action.RETRY_HOST_IDENTITY_HOURLY
        if (!isJsch) return Action.RETRY_LOCAL_HOURLY

        // mwiede/JSch 2.x emits e.g. "Auth fail for methods 'publickey'".
        val knownAuthRejection = exceptionClassName == "JSchAuthCancelException" ||
            message == "Auth fail" || message?.startsWith("Auth fail for methods '") == true ||
            message == "Auth cancel" || message?.startsWith("Auth cancel for methods '") == true ||
            message?.startsWith("Too many authentication failures") == true
        return if (knownAuthRejection) Action.RETRY_AUTH_HOURLY else Action.RETRY_NETWORK
    }
}
