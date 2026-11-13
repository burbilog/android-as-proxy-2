package net.isaeff.android.asproxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class EnrollmentInputTest {
    @Test fun acceptsHostAndOptionalPort() {
        assertEquals(EnrollmentInput.Endpoint("example.net", 22),
            EnrollmentInput.endpoint("example.net"))
        assertEquals(EnrollmentInput.Endpoint("192.0.2.3", 22222),
            EnrollmentInput.endpoint("192.0.2.3:22222"))
        assertEquals(EnrollmentInput.Endpoint("2001:db8::1", 22),
            EnrollmentInput.endpoint("[2001:db8::1]"))
        assertEquals(EnrollmentInput.Endpoint("2001:db8::1", 2022),
            EnrollmentInput.endpoint("[2001:db8::1]:2022"))
    }

    @Test fun rejectsUnsafeEndpoints() {
        for (input in listOf("https://host", "user@host", "host/path", "host:0",
                             "host:65536", "host:", "[::1]bad", "::1", " -x",
                             "host,addr=1.2.3.4", "")) {
            assertThrows(input, IllegalArgumentException::class.java) {
                EnrollmentInput.endpoint(input)
            }
        }
    }

    @Test fun pinPreservesLeadingZerosButNotSeparators() {
        assertEquals("0000000012", EnrollmentInput.pin("0000-0000-12"))
        assertEquals("0123456789", EnrollmentInput.pin("0123 4567 89"))
        for (value in listOf("012345678", "12345678901", "0123-45x7-89")) {
            assertThrows(IllegalArgumentException::class.java) {
                EnrollmentInput.pin(value)
            }
        }
    }

    @Test fun setupUserMustBeServerGenerated() {
        assertEquals("aap2e123456abcdef",
            EnrollmentInput.setupUser("aap2e123456abcdef"))
        for (value in listOf("root", "aap2p123456abcdef", "aap2eXX", "--root")) {
            assertThrows(IllegalArgumentException::class.java) {
                EnrollmentInput.setupUser(value)
            }
        }
    }

    @Test fun fullFingerprintGroupsWithoutTruncating() {
        assertEquals("SHA256:abcd-efgh-ij",
            EnrollmentInput.groupedFingerprint("SHA256:abcdefghij"))
    }
}
