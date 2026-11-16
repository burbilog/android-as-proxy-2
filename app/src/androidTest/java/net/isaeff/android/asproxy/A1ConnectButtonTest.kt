package net.isaeff.android.asproxy

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Only clicks the isolated UI button: NEVER starts real AAP2 or touches
 * a device's enrolled SSH identity. Also works with notifications disabled.
 */
class A1ConnectButtonTest {
    @get:Rule val rule = createComposeRule()

    @Test fun connectAlwaysCallsStartWithoutNotificationPermissionGate() {
        var starts = 0
        rule.setContent { ConnectButton(onConnect = { starts++ }) }
        rule.onNodeWithTag("a1-connect").performClick()
        rule.runOnIdle { assertEquals(1, starts) }
    }
}
