# UC: Open a Memory & browse its gallery

- **ID:** uc.memory.gallery
- **Primary actor:** A member with an **unlocked vault**, inside one of their spaces, who **opens a Memory**
  from the shell.
- **Goal:** Open a Memory and look through its media — a thumbnail **grid**, an item **lightbox** (full view,
  prev/next), and **download** an original — with every byte decrypted **on device**.
- **Preconditions:** The user has unlocked the vault and is browsing the tree
  ([uc.memory.browse](uc.memory.browse.md)); the Memory exists and its **`K_mem`** can be unwrapped; its media
  carry **derivatives** ([en.memory.derivatives](../enablers/en.memory.derivatives.md) — see *Scope* for media
  that don't yet).
- **Postconditions (success):** The user sees the Memory's media as a grid, opens any item in a lightbox and
  pages through, can favorite the Memory, and can download an item's original. The server saw only **ciphertext
  fetches** — no pixel, no filename, no content key.

## Main flow

1. From the shell ([uc.memory.browse](uc.memory.browse.md)) the user clicks a Memory → it **opens**: the
   client unwraps the Memory's **`K_mem`** (already held from the tree).
2. The client **lists the Memory's media** ([en.memory.media](../enablers/en.memory.media.md)) and fetches each
   item's **thumbnail** ([en.memory.derivatives](../enablers/en.memory.derivatives.md)) via presigned GET,
   decrypts on device, and renders a **grid** under a header (title, item count, favorite toggle).
3. **Lightbox:** clicking an item opens it full-view; the client fetches that item's **preview**, decrypts, and
   displays it; **prev/next** page through the grid order. (The preview — not the multi-MB original — keeps the
   lightbox fast.)
4. **Download:** an explicit download fetches the **original** (the only pristine, full-resolution copy) and
   saves it.
5. **Favorite:** the header toggle pins / unpins the Memory ([en.memory.favorites](../enablers/en.memory.favorites.md)).

## Scope of this slice

- **In:** **open** a Memory; the thumbnail **grid**; the **lightbox** (preview, prev/next); **download
  original**; a header (title, item count, favorite toggle). Media attached **directly to the Memory node**.
- **Out (later slices / their own use-cases):** the **journal** (`uc.memory.journal`); **chapters** &
  chapter-scoped views (`uc.memory.chapters`) — so media nested **inside a chapter is not aggregated** into this
  gallery yet; **map / tracks**; **playlists**; per-uploader **buckets** + a *mine / group* filter;
  **participants**; a chosen **cover** (the grid/lightbox use derivatives; a dedicated cover image is later);
  **sharing**.
- **Depends on:** [en.memory.derivatives](../enablers/en.memory.derivatives.md) (**built first** — the
  grid/lightbox/download tiers), [en.memory.media](../enablers/en.memory.media.md),
  [en.memory.favorites](../enablers/en.memory.favorites.md).

## Alternate / exception flows

- **E1 — an item has no derivable frame** (a type that is neither image nor video): the grid shows an **icon +
  name**. (There is **no backfill** — on a fresh instance every image/video media carries its derivatives from
  upload, [en.memory.derivatives](../enablers/en.memory.derivatives.md).)
- **E2 — vault locked / Memory not open:** without `K_mem` the media can't be read → the gallery is unavailable
  until unlock + the Memory is opened.
- **E3 — empty Memory:** the grid shows an empty state.

## Quality notes

- **Operator-blind.** Every tier is fetched as **ciphertext** and decrypted on device; the server never sees a
  pixel, a filename, or a content key. The lightbox shows the **preview**, not the original — the original
  leaves the store only on explicit download.
- **Reuse the existing UI/UX — never invent.** The grid + lightbox are ported from the pre-E2EE design source
  (`legacy/frontend-noe2ee/`, see its `_PROVENANCE.md`); only the **data layer** is swapped to the contracts
  client + client-side crypto.
- **Web first, then native parity** ([ADR 0010](../../../decisions/0010-native-clients-shared-contracts-not-code.md)):
  web → apple → android — the shells already host the *open* hook ([uc.memory.browse](uc.memory.browse.md)).

## What this means for you / Was das für dich bedeutet

**EN —** Open a Memory and you see its photos as a grid; tap one to view it large and swipe through the rest.
Everything is decrypted on **your own device** — the server only ever stores scrambled files and never sees
your pictures. To keep it quick, the grid and the large view use **compact copies your device made**; the
**full-quality original is only fetched when you actually download it**. Journal, chapters, maps and playlists
come in later steps.

**DE —** Öffne eine Erinnerung und du siehst ihre Fotos als Raster; tippe eines an, um es groß zu sehen und
durch die anderen zu blättern. Alles wird auf **deinem eigenen Gerät** entschlüsselt — der Server speichert nur
verschlüsselte Dateien und sieht deine Bilder nie. Damit es flott bleibt, nutzen das Raster und die Großansicht
**kompakte Kopien, die dein Gerät erstellt hat**; das **Original in voller Qualität wird nur geladen, wenn du es
wirklich herunterlädst**. Journal, Kapitel, Karten und Playlists kommen in späteren Schritten.

## Related

- Feature: [Memory](../_index.md)
- Enablers: [en.memory.derivatives](../enablers/en.memory.derivatives.md) (the three tiers),
  [en.memory.media](../enablers/en.memory.media.md) (the blob transport),
  [en.memory.favorites](../enablers/en.memory.favorites.md) (the favorite toggle),
  [en.memory.tree](../enablers/en.memory.tree.md) (`K_mem`)
- Decisions: [ADR 0013](../../../decisions/0013-blob-storage-presigned-direct-to-store.md) (direct-to-store
  media), [ADR 0012](../../../decisions/0012-content-key-hierarchy.md) (`K_mem` → `K_blob`),
  [ADR 0010](../../../decisions/0010-native-clients-shared-contracts-not-code.md) (web first; native parity)
- Related use-cases: [uc.memory.browse](uc.memory.browse.md) (done — the shell this opens from);
  the journal / chapters / map — planned
