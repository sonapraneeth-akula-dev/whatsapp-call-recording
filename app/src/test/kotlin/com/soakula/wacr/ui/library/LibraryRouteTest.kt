package com.soakula.wacr.ui.library

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

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
}
