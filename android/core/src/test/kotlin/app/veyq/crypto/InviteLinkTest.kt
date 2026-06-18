package app.veyq.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Native parity for invite-link build/parse — byte-compatible with the web and iOS parsers
// (frontend/src/features/spaces/invite/inviteLink.ts; mirrors inviteLink.test.ts).
class InviteLinkTest {
    @Test fun roundTripsPragmaticInviteThroughFragment() {
        val p = InviteLink.parse(InviteLink.build("https://veyq.example", "inv1", "boot", "tkey"))
        assertNotNull(p)
        assertEquals("inv1", p.inviteId)
        assertEquals("boot", p.bootstrapSecret)
        assertEquals("tkey", p.tempPrivateKey)
        assertFalse(p.isProtected)
    }

    @Test fun roundTripsPassphraseProtectedInvite() {
        val p = InviteLink.parse(InviteLink.buildProtected("https://veyq.example", "inv1", "boot", "enc", "salt"))!!
        assertEquals("enc", p.encTempKey)
        assertEquals("salt", p.pwSalt)
        assertNull(p.tempPrivateKey)
        assertTrue(p.isProtected)
    }

    @Test fun keepsSecretsInFragmentNotQuery() {
        val link = InviteLink.build("https://veyq.example", "inv1", "boot", "tkey")
        assertTrue(link.contains("#invite="))
        assertFalse(link.contains("?"))  // secrets ride the fragment, never the query
        val frag = link.substringAfter('#')
        assertTrue(frag.contains("s=boot"))
        assertTrue(frag.contains("k=tkey"))
    }

    @Test fun returnsNullForNonInviteHashes() {
        assertNull(InviteLink.parse("https://veyq.example/#k=onlykey"))  // no invite / s
        assertNull(InviteLink.parse("#foo=bar"))
        assertNull(InviteLink.parse("not a link"))
    }

    @Test fun parsesInviteDeepLinkWithInstance() {
        val dl = InviteLink.parseDeepLink("veyq://invite#invite=i1&s=boot&k=tkey&api=https://veyq.example/")
        assertEquals("https://veyq.example", dl?.apiBase)  // the banner carries the instance; trailing slash trimmed
        assertEquals("i1", dl?.parts?.inviteId)
        assertEquals("tkey", dl?.parts?.tempPrivateKey)
    }

    @Test fun deepLinkRequiresInstanceAndInvite() {
        assertNull(InviteLink.parseDeepLink("veyq://invite#invite=i1&s=boot&k=tkey"))         // no api= → not a deep link
        assertNull(InviteLink.parseDeepLink("veyq://open#path=/x&api=https://veyq.example"))  // not an invite
    }
}
