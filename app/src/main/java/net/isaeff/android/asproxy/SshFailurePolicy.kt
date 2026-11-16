package net.isaeff.android.asproxy

/**
 * Pure failure classification: no PIN/host-key changes and no message shown to
 * the nontechnical user. Technical exception strings remain diagnostics only.
 */
internal object SshFailurePolicy {
    enum class Action { FATAL_HOST_IDENTITY, FATAL_LOCAL, RETRY_AUTH_HOURLY, RETRY_NETWORK }
    const val AUTH_RETRY_MS = 60 * 60 * 1000L

    fun classify(className: String, message: String?, isJsch: Boolean): Action {
        val name = className.lowercase()
        val detail = message.orEmpty().lowercase()
        if (name.contains("hostkey") || detail.contains("hostkey") ||
            detail.contains("host key") || detail.contains("known_hosts") ||
            detail.contains("fingerprint")) return Action.FATAL_HOST_IDENTITY
        if (isJsch && (name.contains("auth") || detail.contains("auth fail") ||
                detail.contains("auth cancel") || detail.contains("userauth") ||
                detail.contains("too many authentication failures"))) {
            return Action.RETRY_AUTH_HOURLY
        }
        return if (isJsch) Action.RETRY_NETWORK else Action.FATAL_LOCAL
    }
}
