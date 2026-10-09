# Idea: let others create their own households

Status: **not started, not yet discussed in detail** (noted 2026-10-09).
Roadmap entry: "Later / ideas" in `ROADMAP.md`.

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
