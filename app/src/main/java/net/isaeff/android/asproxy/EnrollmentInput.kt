package net.isaeff.android.asproxy

/** Minimal, strict input parsing. No URL schemes, SSH usernames or paths in Server. */
internal object EnrollmentInput {
    data class Endpoint(val host: String, val port: Int)

    fun endpoint(input: String): Endpoint {
        val value = input.trim()
        require(value.isNotEmpty() && value.length <= 300 &&
            value.none { it.isWhitespace() || it == '/' || it == '@' || it == '\\' }) {
            "Enter a host or host:port, not a URL"
        }
        val host: String
        val port: Int
        if (value.startsWith("[")) {
            val end = value.indexOf(']')
            require(end > 1) { "Invalid bracketed IPv6 address" }
            host = value.substring(1, end)
            val rest = value.substring(end + 1)
            require(rest.isEmpty() || rest.startsWith(":") && rest.length > 1) {
                "Invalid SSH port"
            }
            port = if (rest.isEmpty()) 22 else rest.substring(1).toIntOrNull() ?: -1
        } else {
            require(value.count { it == ':' } <= 1) { "Use [IPv6]:port for IPv6" }
            host = value.substringBefore(':')
            val rest = if (value.contains(':')) value.substringAfter(':') else ""
            port = if (!value.contains(':')) 22 else rest.toIntOrNull() ?: -1
        }
        require(host.matches(Regex("[A-Za-z0-9._:-]{1,253}")) &&
            host != "." && host != ".." && !host.startsWith("-")) {
            "Invalid SSH hostname/address"
        }
        require(port in 1..65535) { "SSH port must be 1..65535" }
        return Endpoint(host, port)
    }

    fun setupUser(value: String): String {
        val code = value.trim()
        // Human entry: only six numeric digits, with leading zeros intact.
        // Keep full 12-hex S2 account names valid for older invitations.
        if (code.matches(Regex("[0-9]{6}"))) return "aap2e" + code
        require(code.matches(Regex("aap2e(?:[0-9]{6}|[0-9a-f]{12})"))) {
            "Enter six-digit setup code (or complete legacy setup username)"
        }
        return code
    }

    fun pin(value: String): String {
        val digits = value.replace("-", "").replace(" ", "")
        require(digits.matches(Regex("[0-9]{10}"))) {
            "One-time PIN must contain exactly ten digits"
        }
        return digits
    }

    fun groupedFingerprint(fingerprint: String): String {
        require(fingerprint.startsWith("SHA256:"))
        return "SHA256:" + fingerprint.removePrefix("SHA256:").chunked(4).joinToString("-")
    }
}
