# UC: Change account email

- **ID:** uc.account.change-email
- **Primary actor:** Memory Curator
- **Goal:** Change the account login email to a new address, proving control of the new inbox.
- **Preconditions:** Authenticated; the desired new address is not already in use by another account.
- **Postconditions (success):** The account's login email is swapped to the new address; the change is
  applied only after the new inbox is confirmed; existing sessions remain valid (email is not part of key
  derivation, so **no key material is re-wrapped**).

## Main flow
1. The authenticated user submits the **new address** to the request endpoint.
2. The server checks the address is **free** and emails a one-time, token-gated confirmation link to the
   **new address** (control of the new inbox is the proof).
3. The user opens the link; the confirm endpoint consumes the **single-use** token and swaps the account
   email.
4. The same still-valid session now reports the new email.

## Alternate / exception flows
- A1: A new request supersedes any prior pending token (one active token per user).
- E1: The new address is **already in use** → the request is rejected (409) and no email is sent.
- E2: The confirmation token is **invalid, expired or already used** → the confirm is rejected (400).
- E3: The request is made **without a valid session** → rejected (401).

## Quality notes
- *Identifier swap, not a key change:* the email is **not** part of the KEK (Argon2id over Secret Key +
  password + a random salt), so changing it re-wraps no key material — unlike the credential changes in
  [manage-profile](uc.account.manage-profile.md).
- *Proof = control of the new inbox:* confirm is token-only (like password reset); possession of the
  emailed token proves the requester controls the destination address.
- *Single-use, time-boxed token:* the token is stored hashed, expires, and is consumed on first confirm.

## Related
- Feature: [Account & Auth](../_index.md) · Actors: [Memory Curator](../../../actors.md)
- Concepts: [Security Model](../../../crosscutting/security-model.md) (key hierarchy — email is outside it),
  [Platform Concepts](../../../crosscutting/platform-concepts.md) (auth & sessions)
- Decisions: [AD-08](../../../decisions/README.md) (Secret Key), [AD-31](../../../decisions/0031-profile-fields-server-visible.md) (profile fields server-visible)
- Related use-cases: `uc.account.manage-profile`, `uc.account.login`, `uc.account.recover-keys`
