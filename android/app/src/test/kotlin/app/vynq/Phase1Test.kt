package app.vynq

import app.vynq.data.auth.EmailValidator
import app.vynq.data.prefs.CallQuality
import app.vynq.data.prefs.themeModeFromKey
import app.vynq.designsystem.theme.ThemeMode
import app.vynq.ui.settings.asMbps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmailValidatorTest {
    @Test fun acceptsOrdinaryAddresses() {
        assertTrue(EmailValidator.isValid("luna@vynq.app"))
        assertTrue(EmailValidator.isValid("  Luna.Kai+test@Example.co.uk "))
    }

    @Test fun rejectsObviousTypos() {
        listOf("", "luna", "luna@", "@vynq.app", "luna@vynq", "luna @vynq.app", "a@b.c").forEach {
            assertFalse("expected invalid: '$it'", EmailValidator.isValid(it))
        }
    }

    @Test fun normalizesCaseAndWhitespace() {
        assertEquals("luna@vynq.app", EmailValidator.normalize("  LUNA@Vynq.App "))
    }
}

class PreferenceMappingTest {
    @Test fun unknownOrMissingKeysFallBackToSafeDefaults() {
        assertEquals(ThemeMode.System, themeModeFromKey(null))
        assertEquals(ThemeMode.System, themeModeFromKey("Neon"))
        assertEquals(CallQuality.Automatic, CallQuality.fromKey(null))
        assertEquals(CallQuality.Automatic, CallQuality.fromKey("Ultra"))
    }

    @Test fun storedKeysRoundTrip() {
        ThemeMode.entries.forEach { assertEquals(it, themeModeFromKey(it.name)) }
        CallQuality.entries.forEach { assertEquals(it, CallQuality.fromKey(it.name)) }
    }
}

class FormattingTest {
    @Test fun bandwidthShowsDashWhenUnknown() {
        assertEquals("—", 0.asMbps())
        assertEquals("—", (-1).asMbps())
    }
}
