# UC: Rate-limit abusive traffic

- **ID:** uc.platform.rate-limiting
- **Primary actor:** Server Owner
- **Goal:** Protect the blind server from floods and abuse with per-key request + byte-budget limits,
  keyed by the **real** client behind trusted proxies.
- **Preconditions:** The server is running; trusted-proxy CIDRs are configured (possibly none).
- **Postconditions (success):** Traffic within the limit is served; traffic over the per-key window or
  byte budget is rejected; the limit key reflects the true client even behind a trusted proxy.

## Main flow
1. A request arrives; the filter derives the **client key** — the socket peer, or the rightmost
   non-proxy `X-Forwarded-For` hop when the peer is a configured trusted proxy.
2. The limiter checks the **fixed window** for that key — both a request **count** and a **byte budget**.
3. Within the limit → the request is **served**; over the limit → it is **rejected**.
4. After the window interval **elapses**, the counters **reset**.

## Alternate / exception flows
- A1: **No trusted proxies** configured → `X-Forwarded-For` is ignored; the socket peer is the key.
- A2: **Untrusted peer** → `X-Forwarded-For` is ignored even if present.
- A3: **Trusted proxy, no usable XFF** → fall back to the socket peer.
- E1: The **byte budget** trips independently of the request count once exceeded.
- E2: **Non-positive** byte counts are ignored (no underflow).
- E3: Trusted-proxy specs accept **exact IPs, IPv6 and non-canonical CIDRs** (host bits set still trust
  the whole subnet).

## Quality notes
- *Security:* abuse resistance at the **blind** server — limits use only connection metadata, never
  content.
- *Correctness:* the client key must not be **spoofable** via `X-Forwarded-For` from an untrusted peer.
- *Engineering-trust:* this protects shared infrastructure; it maps to no single product use-case
  (`capabilities: []`).

## Related
- Feature: [Platform](../_index.md) · Actors: [Server Owner](../../../actors.md)
- Concepts: [Platform Concepts](../../../crosscutting/platform-concepts.md),
  [Security Model](../../../crosscutting/security-model.md)
- Related use-cases: `uc.admin.setup-server`
