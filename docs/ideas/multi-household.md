# Idea: let others create their own households

Status: **decided 2026-10-11, being built as Phase 9 (2.0.0)** (noted 2026-10-09).
Roadmap entry: Phase 9 in `ROADMAP.md`.

## Decisions (2026-10-11)

1. **Who creates a household: admins invite admins.** Any household admin makes a one-time invite in
   Settings (admin PIN) and shares it. A friend uses it to create their own, fully separate household and
   becomes its admin. Defaults: invites expire after **7 days**, **3 per household** in total (cancelling an
   unused one gives the slot back). The server owner's own household starts the chain. No `SETUP_TOKEN`
   any more.
2. **Joining: one generated code plus a share link.** Each household gets a server-made code like
   `HZ7K-2P9X-QM4T`: the first part finds the household, the rest is the secret (hashed as today). Members
   type the code or tap a link that fills it in. Existing households get a new code; joined phones are
   unaffected.
3. **Several households per phone.** A phone can be in more than one household (a member in each). When
   it's in more than one, the app opens on a **household picker**; with one, it goes straight in. On the
   picker each household has a **switch for its reminders and notifications** (on by default); a switched
   off household still syncs in the background.
4. **Also in 2.0:** an admin can **delete their household** and all its data (admin PIN + confirmation);
   a short **privacy note** in the app; **auto-cleanup** of households no phone has contacted for **12 months**:
   marked, then deleted **30 days** later unless a phone checks in. Changed from "after warning the admin" on
   2026-10-11: every installed app checks in every 15 minutes, so a household only looks unused once no phone
   is talking to the server, and then no warning could reach anyone.
5. **Owner tool: minimal, no names.** A command on the VM lists households by id, member count, created
   and last-active dates, and who invited whom, and can remove a household together with the ones it
   invited. Nothing that identifies people. The privacy note says exactly this, and that the server owner
   can technically read the database.

## Goal

Today the server hosts exactly one household. The idea is to let other people, such as
friends or family elsewhere, download the same app, create their own household, and use it
completely separately from ours, on the same server.

## How it works today (why it's blocked)

Two separate locks stop a second household:

1. **The database allows only one row.** `V2__uuid_sync_schema.sql` creates
   `household_singleton`, a unique index on a constant, so a second `INSERT INTO household`
   fails. `HouseholdStore.createHousehold` turns that into `409 "A household already exists"`.
2. **Creating a household needs the server's `SETUP_TOKEN`** (in `deploy/.env` on the VM). It was
   cleared after the first household was created, so `POST /household` answers
   `403 "Creating a household is disabled on this server."`

## What assumes a single household

These places need changing. Line numbers are as of commit `1e07c7b`.

### Server

- **`POST /join`** (`Routing.kt`) calls `store.getHousehold()` with no id, takes *the* household,
  and checks the code against its `code_hash`. With many households the server must first work out
  **which household a code belongs to**. Codes are hashed with PBKDF2 (slow and salted on purpose),
  so the server can't search for a matching hash. Options:
  - Give each household a short **public join id** (e.g. `HZ7-K2P`) used together with the secret
    code: the id finds the household, the code is verified as today. Simple and keeps PBKDF2.
  - Or store an extra **HMAC of the code** (a server-side secret "pepper") in an indexed column for
    lookup, and keep PBKDF2 for verification.
  - Either way, generated codes are better than chosen ones: two households could pick the same
    code.
- **`GET /status`** (`householdExists`) only says whether *a* household exists. The app uses it
  during setup. It becomes meaningless with many, so drop it or change what it means.
- **`POST /household`**: decide how creating is allowed without the server owner's `SETUP_TOKEN`
  (see "Decisions to make").
- **`AttemptLimiter`** is one global counter (all traffic arrives through Caddy, so per-IP limits
  don't work). With many households, one household's wrong codes would lock out every household.
  It probably needs to count **per household** (by join id), plus a global limit against guessing
  across households.
- **Scoping is mostly already done.** Devices, members, chores, completions and join requests all
  carry `household_id`, and `ChoreStore`, `HouseholdStore` and `SyncStore` filter by it. Sync works
  on the device's member, and invites are checked to be in the same household
  (`allActiveMembers`). Still, **audit every query** for a missing `household_id` filter before
  allowing a second household: a missing filter would leak data between households.
- `deleteOrphanedSharedReminders()` runs across all households. That's harmless, but check it again.

### App

- The welcome screen's **"Set up a new household"** asks for the server's setup code. It would
  instead create a household directly (perhaps with a confirmation step) and then **show the
  generated join id and code** for sharing.
- The join screen would ask for the join id plus the code. Alternatively the admin shares a
  **join link** that fills both in.
- Texts that say "the household" / "the server allows one household" need a pass.

### Operations

- **Backups** (still on the roadmap) become more important: one database holds everyone's data.
- **Storage and cleanup**: households that are created and abandoned pile up. Consider deleting
  households with no activity for N months, after warning the admin.
- **A household admin deleting their household** (and all its data) is needed for privacy.
- **The server owner** may want a list of households (names, member counts, last activity) and a
  way to remove abusive ones.

## Decisions to make

1. **Who may create a household?** Anyone with the app (open), anyone with an invite code from the
   server owner, or only when the server owner approves it (like join requests, but one level up)?
2. **Join id + code, or a join link** (or both)?
3. **Limits**: households per phone or per day, members per household, reminders per member?
4. **Privacy**: other households' data must never be visible. Do we write down a short privacy note
   for people outside our household, since they're trusting our server with their data?
5. **Leaving a household / switching households** on a phone (today a phone belongs to one
   household and one member).

## Rough plan once decided

1. Server migration: drop `household_singleton`, add the join id (unique) and the lookup column
   if needed. The existing household gets a join id.
2. Server: `POST /join` finds the household by join id; per-household attempt limits; new create
   rules; audit queries; tests showing two households can't see each other's members, chores,
   reminders, invites or join requests.
3. App: new create flow and showing the join details; join screen with id + code (and link).
4. Ops: backups first, then household deletion and cleanup.
