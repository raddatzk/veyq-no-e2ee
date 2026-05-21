# ADR 0004: Remove Album Product Model

## Status

Accepted

## Context

Veyq originally used Explorer, Folder, Album and Media flows as the visible library model. The product direction has since moved to nested Memories as the only rich content object.

Keeping Album as a parallel product/API concept creates duplicate UX, duplicate permissions behavior and unclear ownership semantics for media.

## Decision

Remove Album and Explorer as product-facing and public API concepts. Memory UI v1, Memory navigation, Folder navigation and Memory media APIs are the active baseline.

Folders remain available only as organizational containers around Memories. Because Veyq is not yet in production, historical album tables are removed instead of kept for compatibility migrations.

## Consequences

New work must target Memory APIs and Memory UI. Album endpoints, Album services, Explorer endpoints, old upload-session APIs, generated clients and database tables should not be restored unless a future ADR explicitly reverses this decision.
