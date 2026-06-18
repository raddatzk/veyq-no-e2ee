package app.veyq.crypto

import kotlin.test.Test
import kotlin.test.assertEquals

// Native parity for the space-path selector + slug — byte-compatible with the web and iOS versions
// (frontend/src/features/home/homeRoute.ts; mirrors homeRoute.test.ts).
class SpacePathTest {
    @Test fun parsesPersonalGroupAndTenantPrefixes() {
        val personal = SpacePath.parsePath("/~me/a/b")
        assertEquals("PERSONAL", personal.kind)
        assertEquals(listOf("a", "b"), personal.segments)

        val group = SpacePath.parsePath("/@reisegruppe/urlaube")
        assertEquals("GROUP", group.kind)
        assertEquals("reisegruppe", group.groupSlug)
        assertEquals(listOf("urlaube"), group.segments)

        val tenant = SpacePath.parsePath("/urlaube/italien")
        assertEquals("TENANT", tenant.kind)
        assertEquals(listOf("urlaube", "italien"), tenant.segments)

        assertEquals("TENANT", SpacePath.parsePath("/").kind)
        assertEquals(emptyList<String>(), SpacePath.parsePath("/").segments)
    }

    @Test fun slugifyMatchesWebRoundTrip() {
        assertEquals("reise-gruppe", SpacePath.slugify("Reise Gruppe"))
        assertEquals("uber-alles", SpacePath.slugify("Über  Alles!!"))  // diacritics folded, runs collapsed
        assertEquals("item", SpacePath.slugify("---"))                  // nothing left after stripping
        assertEquals("ab", SpacePath.slugify("Ab"))
    }
}
