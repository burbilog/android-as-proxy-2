package net.isaeff.android.asproxy

import androidx.compose.ui.text.AnnotatedString
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

    @Test fun manualSetupCodeExpandsToRestrictedUnixAccount() {
        assertEquals("aap2e000012", EnrollmentInput.setupUser("000012"))
        assertEquals("aap2e123456", EnrollmentInput.setupUser("123456"))
        assertEquals("aap2e000012", EnrollmentInput.setupUser("aap2e000012"))
        for (bad in listOf("00012", "0000123", "12-3456", "12 3456", "abcdef")) {
            assertThrows(bad, IllegalArgumentException::class.java) {
                EnrollmentInput.setupUser(bad)
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

    @Test fun connectionIdMatchesServerListForNewAndLegacyAccounts() {
        assertEquals("000012", ConnectionIdentity.id("aap2p000012"))
        assertEquals("692103", ConnectionIdentity.id("aap2p692103"))
        assertEquals("3c0292d79ef2", ConnectionIdentity.id("aap2p3c0292d79ef2"))
        // Existing P0 profiles have a named account, not an S2 numeric ID.
        // The diagnostics screen must still load and identify them.
        assertEquals("aap2user", ConnectionIdentity.id("aap2user"))
    }

    @Test fun activationCodeSeparatorsAppearAtGroupBoundaryAndVanishWithBackspace() {
        val entered = "123456789012345678"
        assertEquals("", EnrollmentInput.formatActivationCode(""))
        assertEquals("123", EnrollmentInput.formatActivationCode("123"))
        assertEquals("1234-", EnrollmentInput.formatActivationCode("1234"))
        assertEquals("1234-5", EnrollmentInput.formatActivationCode("12345"))
        assertEquals("1234-5678-", EnrollmentInput.formatActivationCode("12345678"))
        assertEquals("1234-5678-9012-", EnrollmentInput.formatActivationCode("123456789012"))
        assertEquals("1234-5678-9012-3456-", EnrollmentInput.formatActivationCode(entered.take(16)))
        assertEquals("1234-5678-9012-3456-78", EnrollmentInput.formatActivationCode(entered))
        // Raw editable state is ONLY digits: one backspace deletes a digit
        // and the automatic dash vanishes in the same transformation pass.
        for (length in listOf(4, 8, 12, 16)) {
            assertEquals(
                EnrollmentInput.formatActivationCode(entered.take(length - 1)),
                EnrollmentInput.formatActivationCode(entered.take(length).dropLast(1))
            )
            assertEquals(length - 1,
                EnrollmentInput.formatActivationCode(entered.take(length - 1)).count { it.isDigit() })
        }
    }

    @Test fun activationCodeCursorSkipsVisualSeparatorBothVisibleAndMasked() {
        val entered = "123456789012345678"
        for (reveal in listOf(true, false)) {
            val transform = GroupedActivationTransformation(reveal)
            for (length in 0..18) {
                val digits = entered.take(length)
                val formatted = transform.filter(AnnotatedString(digits))
                val visible = if (reveal) digits else "•".repeat(length)
                assertEquals(EnrollmentInput.formatActivationCode(visible), formatted.text.text)
                val mapping = formatted.offsetMapping
                // Raw caret at end must be AFTER the trailing dash (if any).
                assertEquals(formatted.text.length, mapping.originalToTransformed(length))
                assertEquals(length, mapping.transformedToOriginal(formatted.text.length))
                for (offset in 0..length) {
                    val displayed = mapping.originalToTransformed(offset)
                    assertEquals(offset, mapping.transformedToOriginal(displayed))
                }
                for (offset in 0..formatted.text.length) {
                    val expected = formatted.text.text.take(offset).count { it != '-' }
                    assertEquals(expected, mapping.transformedToOriginal(offset))
                }
            }
        }
    }

    @Test fun fullFingerprintGroupsWithoutTruncating() {
        assertEquals("SHA256:abcd-efgh-ij",
            EnrollmentInput.groupedFingerprint("SHA256:abcdefghij"))
    }
}
