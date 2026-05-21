# ADR 0001: Memory Product Language

## Status

Accepted

## Context

The current implementation exposes folders, albums and media as the primary library model. The product direction is a memory sharing product where a Memory is the main rich content object.

## Decision

Use `Memory` as the product-facing term for new work. Current folder, album and media implementation details may remain during migration, but new product-facing APIs, UI and documentation should use Memory language.

Folders are organizational only and must not become rich content objects. Albums are treated as the legacy implementation baseline that will be migrated into Memories.

## Consequences

New APIs and frontend surfaces should be Memory-first. Existing album/folder code can stay temporarily for compatibility, but it should not receive new product functionality unless required for migration.
