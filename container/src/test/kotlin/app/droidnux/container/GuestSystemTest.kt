package app.droidnux.container

import org.junit.Assert.assertEquals
import org.junit.Test

class GuestSystemTest {

    @Test
    fun deviceNamesBecomeHostNames() {
        assertEquals("tab-s9-di-riccardo", GuestSystem.sanitize("Tab S9+ di Riccardo"))
        assertEquals("sm-x810", GuestSystem.sanitize("SM-X810"))
        assertEquals("", GuestSystem.sanitize("  ++  "))
        assertEquals("a".repeat(63), GuestSystem.sanitize("a".repeat(70)))
        assertEquals("caf", GuestSystem.sanitize("Café"))
    }
}
