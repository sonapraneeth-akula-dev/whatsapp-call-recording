package com.soakula.wacr.ui.library

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class LibraryRouteTest {
    @Test fun contactArgumentsRoundTripWithoutBecomingAdditionalParameters() {
        assertEquals("library", LibraryRoute().encode())
        assertEquals(LibraryRoute(), LibraryRoute.parse("library"))
        for (contact in listOf("CONTACT:demo contact", "UNVERIFIED:caf\u00E9 + team", "GROUP:\u0928\u092E\u0938\u094D\u0924\u0947",
            "CONTACT:name&other=wrong#fragment", "MANUAL:")) {
            val route = LibraryRoute(contact)
            assertTrue(route.encode().startsWith("library?contact="))
            assertEquals(route, LibraryRoute.parse(route.encode()))
        }
    }

    @Test fun unsupportedMalformedDuplicateAndNoncanonicalRoutesAreRejected() {
        for (value in listOf("other", "https://host/library", "library#part", "library?other=name",
            "library?contact=", "library?contact=one&contact=two", "library?contact=%zz", "library?")) {
            assertThrows(IllegalArgumentException::class.java) { LibraryRoute.parse(value) }
        }
        assertThrows(IllegalArgumentException::class.java) { LibraryRoute(" ") }
    }

    @Test fun completeFilterArgumentsRoundTripAndRejectInvalidDateRanges() {
        val date = LocalDate.of(2026, 10, 19)
        val route = LibraryRoute("CONTACT:demo", date, date.plusDays(1), " caf\u00E9 + & ")
        assertEquals(route, LibraryRoute.parse(route.encode()))
        for (value in listOf(
            "library?from=2026-10-19", "library?to=2026-10-19",
            "library?from=2026-02-30&to=2026-03-01",
            "library?from=2026-10-20&to=2026-10-19",
            "library?from=0000-01-01&to=2026-10-19",
            "library?query=one&query=two", "library?query=",
            "library?from=2026-10-19&from=2026-10-20&to=2026-10-20"
        )) assertThrows(IllegalArgumentException::class.java) { LibraryRoute.parse(value) }
    }
}
