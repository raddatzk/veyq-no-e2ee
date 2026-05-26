# ADR 0009: Memory Chunked Media Uploads

## Status

Accepted

## Context

Large Memory media uploads need visible progress, cancellation and server-side cleanup for incomplete transfers. Veyq previously had upload-session APIs in the old Album/Explorer model, but ADR 0004 removed those product/API concepts.

The web frontend keeps current upload state in memory so uploads can continue while users navigate within the SPA. A full browser reload aborts the running web upload because the browser no longer grants access to the selected local files unless the application stores those files or asks the user to select them again.

## Decision

Add a new Memory-scoped chunked media upload API. The API uses upload sessions and fixed-size chunks for private Memory uploads and Public Share uploads, but does not restore Album, Explorer or legacy `/api/media/uploads` contracts.

Upload session metadata and chunk files are stored under the configured media storage directory. The web frontend does not persist local files in IndexedDB for browser-reload resume in this iteration.

## Consequences

Large uploads can report progress per chunk and can be cancelled while the current tab is alive. Server-side cleanup removes stale sessions after the configured upload-session TTL, and delete flows remove upload sessions for deleted Memories or Tenants.

The OpenAPI endpoints remain "resumable" in the server-side sense: clients can query upload session status, upload missing chunks and complete or abort the session. Browser-reload resume for the web frontend remains deferred future work, potentially via explicit file re-selection, IndexedDB file persistence or the File System Access API. Parallel chunk uploads, automatic retry policy and legacy Album/Explorer compatibility remain deferred.
