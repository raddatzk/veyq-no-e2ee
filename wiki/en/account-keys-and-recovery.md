# Account keys & recovery

A plain-language companion to [Is my data safe?](is-my-data-safe.md). Because veyq is end-to-end
encrypted, **your keys — not the server — guard your data.** Here's how that works, no crypto
background needed.

**A few words you'll see:**
- **Key** — a secret that locks/unlocks data.
- **Master key** — the one key that protects everything else. All your memories hang off it.
- **Secret key** — a high-entropy code in your **Emergency Kit**, combined with your password. Never
  sent to the server.
- **Recovery code** — a single-use backup code that can unlock your account on its own.

## One master key, several ways to open it

There is **one master key**, and it's locked **separately by each method you set up**:

```
master key ─┬─ opened by your password + secret key   (always set up)
            ├─ opened by your recovery code
            └─ opened by this device, once you trust it
```

Any one of them opens the **same** master key, which unlocks **all** your content. Adding or removing a
method just adds or removes one lock — **your memories are never re-encrypted.**

## Logging in

- **First time on a device** — your **password + the secret key** from your Emergency Kit. You type the
  secret key once here, and then not again on this device.
- **A device you trust** — from then on, just your password. Tick **"remember me"** instead and the device
  opens straight to your memories with nothing to type at all.
- **Someone else's computer** — use a private/incognito window; nothing is kept.

The secret key is only needed that first time. Why have it at all? Your password is sent to the server to
log in — if the master key came from the password *alone*, the server could open it. The secret key (never
sent) closes that gap.

## What the server actually stores

Only the **locked** copies of your master key — never anything that opens them. The thing that unlocks a
copy is built on your device for a moment during sign-in, then thrown away. It is never stored and never
sent. That's what keeps the server blind.

## If you lose access — three ways back

- **Another device** — still have one signed in? Just set a new password.
- **Emergency Kit** — your secret key + password get you in on a fresh device.
- **Recovery code** — the last resort: it unlocks your account on its own, then hands you a **fresh kit**
  (new secret key + new recovery code). It's **single-use**.

**Why a forgotten password doesn't lose your memories:** everything is tied to your *key*, not your
password. Get the key back by **any** route and everything returns at once.

**The one thing we can't undo:** lose **every** route — no device, no Emergency Kit, no recovery code —
and your content is gone. There is **no backdoor and no escrow**: the same thing that stops *us* reading
your data stops us rescuing it.
