# UC: Link external playlists

- **ID:** uc.memory.link-playlists
- **Primary actor:** Memory Curator
- **Goal:** Attach external music links (playlist, album or single track) to a Memory and feature one
  in the header.
- **Preconditions:** Authenticated; may edit the Memory; holds the keys.
- **Postconditions (success):** One or more music links are attached (URL + label, encrypted); one is
  featured for the header; each opens as a deep-link.

## Main flow
1. The curator pastes a provider **URL** (Spotify / Apple Music / YouTube, …). Whether it points at a
   **playlist, album or single track doesn't matter** — the link is treated opaquely.
2. The client recognizes the **provider** from the URL to show the correct **icon**; the curator adds a
   **label**.
3. The URL and label are stored **encrypted** as Memory context.
4. The curator marks one link as **featured**; the header offers previous/next across the attached
   links.
5. Opening a link **deep-links** to the provider — the native app if installed, otherwise the web.

## Alternate / exception flows
- A1: No link is featured → the first one is the header default.
- A2: An **unrecognized** provider → a generic link icon; it still opens as a normal external link.
- E1: The pasted text is not a valid URL → rejected with a clear error.

## Quality notes
- *Confidentiality:* the link is **sensitive metadata → encrypted**; the server sees only that the
  Memory has K attachments plus the featured pointer — never the URL or provider.
- *Simplicity (KISS):* v1 stores just **URL + label** — no cover art, no metadata fetch (no external
  call), no playlist/album/track parsing. The provider (for the icon) is derived client-side.
- *Privacy:* opening a link uses `Referrer-Policy: no-referrer` (no path leaks to the provider).
- *Later:* a richer presentation — consent-gated cover/title fetch ([AD-27](../../../decisions/README.md))
  and a real **embedded player** — is a future refinement.

## Related
- Feature: [Memory](../_index.md) · Actors: [Memory Curator](../../../actors.md)
- Concepts: [Security Model](../../../crosscutting/security-model.md) (encrypted metadata)
- Decisions: legacy ADR 0010 (playlist links) — to migrate into [decisions](../../../decisions/README.md)
- Related use-cases: `uc.memory.curate`, `uc.journal.embed`
