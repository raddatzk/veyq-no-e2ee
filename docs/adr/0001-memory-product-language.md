# ADR 0001: Memory Product Language

## Status

Accepted

## Context

The original implementation exposed folders, albums and media as the primary library model. The product direction is a memory sharing product where a Memory is the main rich content object.

## Decision

Use `Memory` as the product-facing term for new work. Memory UI v1 and Memory APIs are now the active baseline.

Folders are organizational only and must not become rich content objects. Albums are removed as a product and public API concept.

## Consequences

New APIs and frontend surfaces must be Memory-first. Album/Explorer flows and tables should not be reintroduced.
