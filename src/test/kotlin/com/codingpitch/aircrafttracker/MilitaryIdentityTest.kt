package com.codingpitch.aircrafttracker

import com.codingpitch.aircrafttracker.aircraft.MilitaryIdentity
import com.codingpitch.aircrafttracker.history.PassageHistory
import com.codingpitch.aircrafttracker.model.Aircraft
import kotlin.test.*

class MilitaryIdentityTest {
    @Test
    fun `military identification separates database matches from callsign guesses`() {
        assertEquals("military", MilitaryIdentity.classify("4ca331", "WOLF1").status)
        assertEquals("military", MilitaryIdentity.classify("43c561", null).status)
        assertEquals("military", MilitaryIdentity.classify("ffffff", null, 1).status)
        assertEquals("possible", MilitaryIdentity.classify("ffffff", "WOLF1").status)
        assertEquals("unknown", MilitaryIdentity.classify("4ca63a", "EIN123").status)
        assertEquals("unknown", MilitaryIdentity.classify("ffffff", "WOLFISH").status)
        assertEquals("unknown", MilitaryIdentity.classify("ffffff", null, 2).status)
    }

    @Test
    fun `one ten km log retains all flights with military identification as a field`() {
        val p = Aircraft("4ca331", "WOLF1", null, null, 90.0, null, null, null, "A7",
            53.4, -6.0, 7.14, 3.86, true, altitudeFeet = 1200,
            militaryStatus = "military", militaryBasis = "Aircraft database flag")
        val military = PassageHistory(null, radiusKm = 10.0)
        military.observe(listOf(p))
        assertEquals("WOLF1", military.snapshot().entries.single().flight)
        assertEquals("military", military.snapshot().entries.single().militaryStatus)
        military.observe(listOf(p.copy(hex = "civil", flight = "EIN123", militaryStatus = "unknown", militaryBasis = null)))
        assertEquals(2, military.snapshot().entries.size)
        military.observe(listOf(p.copy(hex = "outside", distanceKm = 10.01)))
        assertEquals(2, military.snapshot().entries.size)
    }
}
