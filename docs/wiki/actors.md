# Actors

The canonical actors referenced by use-cases. A use-case's **Primary actor** must be one of the
names below (each defined as a `###` heading — the use-case validator checks against these).

These are the *use-case* actors (people interacting to reach a goal); the broader *architecture
stakeholders* are in [Goals, Stakeholders & Quality](overview/goals-stakeholders-quality.md).

### Memory Curator
An authenticated tenant member — the primary user. Creates and curates Memories, uploads media,
writes journals, and decides what to share. Owns the keys to their content.

### Tenant Owner
Administers a tenant: invites/removes members, assigns roles, manages branding and legal terms.

### Group Owner
Administers a collaborative group within a tenant: manages membership.

### Server Owner
The global server administrator (self-host or SaaS). Runs initial setup, creates tenants, manages
global users, backup/upgrade.

### Invited User
A person who received a tenant invitation and must create or link an account before accepting.

### Public Viewer
An external, unauthenticated visitor of a public **view** share. Read-only.

### Public Contributor
An external visitor of a share with write access to journals/tracks/playlists and own uploads;
cannot delete.

### Public Manager
An external visitor of a share with expanded write access (covers, highlighting any media).

### Public Uploader
An external visitor of an **upload-only** link. Sees a simple upload form, never the gallery.

### Depicted Person
A person appearing in content who is not necessarily a user — a first-class privacy subject who may
request removal of a specific medium.
