package app.vynq.data.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckTest {
    @Test fun newerVersionsAreRecognised() {
        assertTrue(UpdateCheck.isNewer("0.7.0", "0.6.0"))
        assertTrue(UpdateCheck.isNewer("v0.6.1", "0.6.0"))
        assertTrue(UpdateCheck.isNewer("1.0", "0.9.9"))
        assertTrue(UpdateCheck.isNewer("0.10.0", "0.9.0"))   // numeric, not alphabetical
    }

    @Test fun sameOrOlderIsNot() {
        assertFalse(UpdateCheck.isNewer("0.6.0", "0.6.0"))
        assertFalse(UpdateCheck.isNewer("0.6.0", "0.6.0-debug"))
        assertFalse(UpdateCheck.isNewer("0.5.9", "0.6.0"))
        assertFalse(UpdateCheck.isNewer("0.6", "0.6.0"))
    }
}
