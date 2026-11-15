package net.isaeff.android.asproxy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI

class ApkUpdaterTest {
    @Test fun onlyHttpsAndKnownUpdateDownloadHosts() {
        for (url in listOf(
            "https://github.com/burbilog/android-as-proxy-2/releases/latest/download/aap2.apk",
            "https://github.com/burbilog/android-as-proxy-2/releases/download/v2.0.0-rc1/aap2.apk",
            "https://release-assets.githubusercontent.com/example?token=abc",
            "https://objects.githubusercontent.com/example"
        )) {
            assertTrue(url, ApkUpdater.allowedDownloadUri(URI(url)))
        }
        for (url in listOf(
            "http://github.com/burbilog/android-as-proxy-2/releases/latest/download/aap2.apk",
            "https://isaeff.net/a",
            "https://github.com:444/a",
            "https://user@github.com/repo",
            "https://github.com.evil.example/download",
            "https://fakegithubusercontent.com/download",
            "https://evil.example/release.apk",
            "file:///sdcard/Download/aap2.apk",
            "https://github.com/release.apk#fragment"
        )) {
            assertFalse(url, ApkUpdater.allowedDownloadUri(URI(url)))
        }
    }

    @Test fun relativeRedirectNeverOverridesHttpsAndDomainGate() {
        val start = URI("https://github.com/burbilog/android-as-proxy-2/releases/latest/download/aap2.apk")
        assertTrue(ApkUpdater.allowedDownloadUri(start.resolve("/burbilog/android-as-proxy-2/releases/download/v2.0.0/aap2.apk")))
        assertFalse(ApkUpdater.allowedDownloadUri(start.resolve("http://evil.example/apk")))
        assertFalse(ApkUpdater.allowedDownloadUri(start.resolve("//evil.example/apk")))
    }
}
