package app.veyq.crypto

import kotlin.test.Test
import kotlin.test.assertEquals

// Native parity for frontend/src/features/search/search.test.ts.
class SearchTest {
    private val items = listOf("Italy 2024", "Italian dinner", "Paris")

    @Test fun matchesCaseInsensitivelyAsASubstring() {
        assertEquals(listOf("Italy 2024", "Italian dinner"), Search.matchByName(items, "ital") { it })
        assertEquals(listOf("Paris"), Search.matchByName(items, "PARIS") { it })
    }

    @Test fun returnsNoHitsForAnEmptyOrWhitespaceQuery() {
        assertEquals(emptyList(), Search.matchByName(items, "") { it })
        assertEquals(emptyList(), Search.matchByName(items, "   ") { it })
    }

    @Test fun returnsNoHitsWhenNothingMatches() {
        assertEquals(emptyList(), Search.matchByName(items, "zzz") { it })
    }

    @Test fun capsResultsAt25() {
        val many = List(40) { "node $it" }
        assertEquals(25, Search.matchByName(many, "node") { it }.size)
    }
}
