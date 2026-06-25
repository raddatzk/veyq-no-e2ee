# ADR 0003: Memory Tree Invariants

## Status

Accepted

## Context

Memories can represent small moments or large journeys. Larger Memories may contain child Memories, and parent Memories aggregate child content.

## Decision

A Memory may have one parent Memory and many child Memories. Parent and child Memories must belong to the same tenant and scope. Cycles are forbidden.

Parent Memories aggregate their own visible assets and visible assets from descendant Memories for read views. Aggregated child assets are not moved into the parent.

## Consequences

The backend must validate parent assignment on create/update. Memory media listing needs an option to include descendants. Sharing and future permissions can apply to a Memory subtree.
