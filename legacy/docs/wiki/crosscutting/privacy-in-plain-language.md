# Your privacy, in plain language

> A plain-language companion to the [Security Model](security-model.md) — the *what it means for you* version.
> The sibling page [Account keys, devices & recovery](account-key-model.md) covers your keys and getting back
> in; this one covers **what veyq can and can't see, what sharing really means, and the honest limits**. No
> crypto background needed.

**The one sentence:** veyq is **end-to-end encrypted** — your photos, videos, journal text and the sensitive
details around them are locked on your own device before they ever reach us, and **we never hold a key to open
them**. We run the building; you hold the only key.

## Think of a self-storage unit

Picture a storage building we operate. You rent a unit and put your own padlock on it — and **we don't have a
copy of that padlock**. We can keep the building running, send you to the right unit, tell you how full it is,
and see *that* the unit exists and who else you've given a key to. **What we cannot do is open it and look
inside.** That's the whole idea, and everything below is just the honest detail of where that line sits.

## What we genuinely can't see

All of this is locked with **your** keys and is gibberish to us and to anyone who steals our database:

- your **photos and videos** (and the smaller preview versions),
- your **journal text**,
- the **sensitive details**: titles, captions, descriptions, places, the people in them, tags,
- **where a photo was taken** (GPS) and **when** it was captured — sealed with the rest, not read off your files.

## What we *can* still see — and why

We're not going to pretend it's magic. To run the service — sort your timeline, show a page, route a request,
enforce who's allowed in — the server does see some **low-sensitivity "operational" facts**:

| We can see | We cannot see |
|---|---|
| that a memory / photo / share **exists**, and its ID | what's **in** it |
| **how big** a file is, and its rough type (e.g. "a video") | the file itself |
| **who is in which** group or space | what they wrote or uploaded |
| coarse **timestamps** used to order your timeline | the captured content behind them |

Analogy: we see the **labelled boxes on the shelf and who holds a key** — never the contents. This is a
deliberate trade-off: those few facts are what let the server be useful without ever decrypting. (A stricter
"hide even those" mode is possible, at the cost of those conveniences.)

## Where your files actually travel

Your photos and videos are kept in a **storage locker** (an object store). When you add or open a big file, it
can take one of two routes — and **both are equally private**, because the file is already locked before it
leaves your device:

- **Straight to the locker.** For large files (full-size photos, videos) your device talks **directly** to the
  storage locker using a **one-time pass** we hand it — good for a few minutes, for that one file only. The
  file skips our app servers entirely. It's faster for you and lighter for us, and we still **never see inside**
  — the locker only ever receives the already-locked box.
- **Through us.** For tiny files — the little thumbnail previews in your gallery — the file rides through our
  app server. For something that small, a quick hop through us is actually faster than fetching a separate pass
  first. Same lock, same blindness: a box passing through our hands is still sealed shut.

Your device decides which route fits, automatically. The honest takeaway: **the route changes the speed, never
the privacy.** Whether a file goes direct to the locker or via us, it's encrypted with your key the whole way,
and the one-time pass only ever lets someone move that single sealed box — never open it.

## When you share

Sharing is the one moment you **choose** to lower the wall — and it's always your explicit action:

- Share with a **group or person** and they can read that content. You've given it up **to them** — not to us;
  we still can't read it.
- A **public link** can be opened by **anyone who has the link**. The secret that unlocks it lives **in the
  link itself** (after the `#`) and never reaches our server, so a public share is "readable by link-holders,
  still not by us."

Two honest limits worth knowing:
- **Un-sharing doesn't un-download.** If someone already opened and saved something, revoking access stops
  *future* access — it can't reach back and erase what they already have. (Same as any file you've sent someone.)
- **Shared means shared.** Once you hand a key to others, confidentiality *from them* is gone by design; only
  confidentiality from *us* still holds.

## Illegal content & abuse

Because private content is invisible to us, we **can't** scan your private memories — that's the point, and we
won't pretend otherwise. Where content is actually **distributed** — a **public share** — an abuse report
includes the link (and therefore the key), so a reported public share **can** be checked, quarantined and
acted on. Notice-and-takedown works exactly where it should: on the public stuff.

## Built to last

Memories are for decades, so the locks are chosen to **resist even future quantum computers** (a real concern:
an attacker could copy encrypted data today and hope to crack it later). That post-quantum protection is **built
in today**, not a someday-promise. And the system is designed so we can **upgrade the locks over time without
re-locking everything you already have**.

## Making sure it's really *their* key — and an honest gap

When you share with a friend, your app needs your friend's public key, which it gets from our server. A
dishonest operator could, in theory, hand your app the *wrong* key. To catch that, every person has a
**fingerprint** (a short "safety number" derived from their key). You and your friend can compare it
**out-of-band** — read it aloud or scan a QR — and if it matches, you know the key is genuinely theirs. It's
like recognizing a friend's voice before trusting a phone call.

**The honest part:** today this check is **manual** — it only protects you if you actually compare. veyq does
**not yet** automatically remember a contact's key and warn you if it later changes, and the stronger
"the server can't lie about keys at all" system (**Key Transparency**) is **still on the roadmap**. So: the
tool to verify exists; the automatic safety net doesn't yet, and we'd rather tell you than imply it does.

> **Self-hosting note:** if *you* run your own veyq server, *you* are the operator — this whole "can the
> operator be trusted" question doesn't apply to your own instance.

## If something goes wrong

- **Forgot your password?** You won't lose your memories — they're tied to your *key*, not your password, and
  there are several ways back in. See [Account keys, devices & recovery](account-key-model.md).
- **Lost everything** — no device, no Emergency Kit, no recovery code? Then it's **gone**. There is **no
  backdoor and no escrow**: the same thing that stops *us* from reading your data stops us from rescuing it.
  That's the deal, stated up front.

## The precise version

This is the friendly tour. The exact mechanisms, threat model and what's built vs. still planned live in the
[Security Model](security-model.md); your keys and recovery in [Account keys, devices & recovery](account-key-model.md).
