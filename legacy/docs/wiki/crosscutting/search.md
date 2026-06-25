# Search

> The server is blind to content ([Security Model](security-model.md)), so **all content search is
> client-side**. The server only helps with **structural** queries over the metadata it can see.

The searchable **text** surface is **small** — Memory titles, folder names, participant names, tags,
journal text — *not* pixels. Even a user's whole accessible corpus is a few MB to low tens of MB of text.

## Three layers

| Layer | Who | Over what |
|---|---|---|
| **Structural / operational** | **Server** | date-range, type, size, scope filter, **sort & paginate** — over the *server-visible operational metadata* (see [encrypted vs. visible](security-model.md)). No content needed. |
| **Full-text** | **Client** | titles, captions, journal, **participants**, **tags** — over content it has decrypted locally. |
| **ML (later)** | **on-device** | **faces** (People context, viewer-scoped) and semantic / visual search. |

## The client builds its own index

A user's searchable set is **individual** — the union of their **personal space + each group they
belong to + tenant-visible content**; no two users have the same set. So the **client builds and
maintains its own local full-text index** (native: SQLite FTS; browser: IndexedDB) over the decrypted
metadata/journal it **already syncs to display content**.

Index-building is therefore a **byproduct of having the data locally**: **no extra download, no worker,
no server involvement.** The index is persisted locally and updated incrementally as content syncs.

> We do **not** pre-build a shared "index derivative". For a small text corpus that would be
> over-engineering. A per-scope encrypted index derivative is only a *far-future* option to speed a
> thin client's first load if metadata corpora ever grow very large — not built now.

## Tags

**Tags are sensitive** → encrypted and client-indexed (a tag can reveal content); never server-visible.

## Query schema

A search is one **structured object**; the executor routes each field to its layer and merges. The same
object is produced either by **UI filters** or by an **on-device LLM** from natural language.

```
SearchQuery {
  // structural → server (visible metadata)
  dateRange?  scope?  mediaType?  inMemory?
  // text → client-local index
  text?  participants?  tags?  place?
  // ML (deferred) → on-device
  semantic?   // concepts over image AND journal-text embeddings
  people?     // participant names → face clusters
  mood?       // optional emotion label
  geo?        // region / terrain / elevation
}
```

**One schema, two front-ends** → graceful degradation (no LLM → UI filters produce the same object),
bounded & testable (the LLM may only emit known fields), extensible (new capability = one field + an
executor). The LLM runs on-device, so the query never leaves the device.

## ML, semantic & natural-language search (deferred)

One pattern throughout: **on-device ML → encrypted artifacts in the DB → synced → local query.** The
server stores only encrypted vectors/labels + visible structural metadata; it never sees content or the
query.

**Two stages.**
1. **Pipeline pre-classification** (once, at processing time, on-device) builds a private encrypted
   "feature store", synced to authorized clients: **image embeddings** (visual semantic),
   **journal-text embeddings** (textual semantic — finds passages about a *feeling/topic*, not just
   keywords), **face clusters** → [People](../overview/architecture.md) participants, optional **mood**
   and **auto-tags/places**.
2. **Query-time orchestration** (light, on-device): a `SearchQuery` built by UI or an **on-device LLM**,
   routed to its layer (server-structural / client-text / on-device nearest-neighbor) and merged. The
   LLM *orients on the pre-classified artifacts* — it does not re-analyze content.

**Multilingual / Denglisch — by design.** Use **multilingual** on-device models. Embeddings are
**language-agnostic**: "sad", "traurig", "war voll sad" land near each other — on both the **content**
side (a Denglisch journal) and the **query** side; the LLM additionally normalizes to canonical filters.

**"Ask your memories" (on-device RAG).** The endpoint: an on-device LLM retrieves relevant journal
passages/media via the embeddings and **answers** — "when was I last really happy?", "tell me about the
summer in Italy" — fully private, because the journal and the query **never leave the device**. Cloud
photo apps cannot do this privately.

> v1 ships **structural + client-side keyword** search. Everything here is **deferred** (on-device
> models; embeddings are large → quantization, scope-limited sync, on-device ANN; capability-gated per
> device). See [Platform Concepts → on-device ML](platform-concepts.md). Decision: [AD-14](../decisions/README.md).
