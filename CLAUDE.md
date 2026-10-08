# CLAUDE.md

Guidance for Claude Code (and humans) working in this repository.

## What this is

**Everyday App** is a private Android app for one household. It has two core features:

1. **Reminders**: custom-message notifications that fire on chosen weekdays, at as many times per day as the user wants.
2. **Chore timetable**: a household-wide weekly schedule of chores, with members linked to each chore.

A small backend on the owner's Oracle Cloud VM stores and syncs the data between the household's phones.

See `ROADMAP.md` for planned features and their order.

## Working with the owner

- The owner wants to be **asked when a decision comes up**. Don't silently pick product behaviour, data model changes, new dependencies, or anything that touches the server. Ask first.
- When asking multiple-choice questions, always include a "Discuss this first" option.

## Repository layout

```
android/   Android app: Kotlin + Jetpack Compose (standalone Gradle build)
server/    API: Kotlin + Ktor + PostgreSQL (standalone Gradle build, Dockerfile)
deploy/    docker-compose stack for the Oracle VM (Postgres, API, Caddy for HTTPS)
```

`android/` and `server/` are **separate Gradle builds on purpose**. Configuring an Android Gradle build requires the Android SDK, and the server image is built on the VM, where the SDK isn't installed. Don't merge them into one root build. If shared models are needed later, see the "Shared models" item in `ROADMAP.md`.

## Decisions made (don't change without asking)

| Topic | Decision |
|---|---|
| App | Native Android, Kotlin, Jetpack Compose, Material 3. `minSdk 28` (Android 9): the oldest household phone runs Android 9. |
| Users | One household, several members |
| Login | **Household code + profile picker.** A phone enters the shared household code once and receives a device token. The person picks their profile once per phone (switchable in Settings), with no password. |
| Household setup | The **first phone creates the household** (name, household code, admin name, admin PIN), using a one-time `SETUP_TOKEN` from the server's `.env`. The server allows exactly one household. |
| Members | Only the **admin** (the household's creator) adds, renames or removes members, and every such request must include the **admin PIN**, which the server checks. The admin can't be removed. |
| Joining | Reminders made on a phone before it joined move to the profile picked on that phone. |
| Backend | Ktor (Kotlin) REST API + PostgreSQL, both in Docker on an Oracle Cloud **Ampere A1 (arm64)** VM |
| HTTPS | Caddy reverse proxy with automatic Let's Encrypt certificates. The owner has **no domain**, so the plan is a free DuckDNS subdomain. |
| Repo | Private GitHub repo `everyday-app` |

## Domain model

- **Household**: one row. Holds the household code hash, the household timezone, the admin member and the admin PIN hash.
- **Member**: a person in the household (display name, colour). Members don't have passwords.
- **Device**: a phone that has joined the household with the code. The server stores a hash of its token.
- **Chore**: shared by the household. Has a title, optional notes, the weekdays it happens on, and a **completion type**:
  - **ANY** ("one person"): done for the day once anyone marks it done. Any member can mark it done or undo it, including members who aren't assigned.
  - **EACH** ("each person"): every assignee does their own part. A member can mark done or undo **only their own part**, and the chore is complete for the day when every assignee has done theirs.
  - Any member can add, edit or delete chores and change assignees. No PIN is needed.
- **Chore assignment**: links members to a chore. Every chore has **at least one** assignee. The timetable is the chores laid out by weekday with their assignees.
- **Chore completion**: one row per (chore, member, day) saying that member did the chore on that day. ANY chores have at most one row per day. The day is the date in the **household timezone**. These rows only drive "done today": there is **no history screen**. Rows are kept (they are tiny).
- **Reminder**: owned by one member. Has a message, a set of weekdays, an active (paused) flag, and a **mode**:
  - **timed**: a normal notification at each of **one or more times of day** (as many as the user wants). It has a **Snooze** action that uses the member's snooze length.
  - **persistent**: an ongoing notification shown during **one or more time windows** (e.g. 16:00-17:00 and 18:00-19:00), with an **"All day"** preset (00:00-23:59). It can't be swiped away. On Android 14+ the system allows swiping, so the app re-posts it while still inside the window. It has a **Done** action, which hides it until the next day. Done can also be set (and undone) from the calendar.
  - Window rules: the end is after the start, no window crosses midnight, and windows of the same reminder don't overlap.
  - It's also either:
    - **general**: `chore_id` is null. It's personal by default, but the owner can **invite other members**. Each invitee is notified of the invite and can **opt in or out** at any time. It fires for the owner and every opted-in invitee. Only the owner edits the message, days and times. Or:
    - **chore reminder**: `chore_id` is set. Only members assigned to that chore can create one, and each member manages their own reminders for the chore. Chore reminders are never shared.
- **Reminder recipient**: an invited member of a general reminder, with status `pending`, `accepted` or `declined`.
- **Member settings** (per member, edited in the app): snooze length in minutes, and **Sound / Vibrate / Silent** chosen separately for timed and persistent reminders.

Weekdays are stored as a 7-bit mask (bit 0 = Monday through bit 6 = Sunday). Reminders repeat **weekly** only. Times of day are local wall-clock times in the household timezone.

The Reminders tab is a **weekly calendar** (7 day columns), not a list.

## Android rules

- **Reminders must fire with no network.** Reminders and their times live in a local Room database and are scheduled on the device with `AlarmManager`. The server is for sync, not for triggering notifications.
- Use exact alarms (`setExactAndAllowWhileIdle`). The manifest declares `USE_EXACT_ALARM` (API 33+) and `SCHEDULE_EXACT_ALARM` with `maxSdkVersion=32`. Still check `canScheduleExactAlarms()`, and fall back to an inexact alarm if it returns false.
- Request `POST_NOTIFICATIONS` at runtime (Android 13+).
- Reschedule all alarms on `BOOT_COMPLETED`, `TIME_SET`, `TIMEZONE_CHANGED`, and app update (`MY_PACKAGE_REPLACED`).
- Scheduling lives in one pure, unit-tested function (`ReminderScheduler`). Given the reminders, done/snooze state and "now", it returns what should be showing now and the next event time. The app keeps **one exact alarm for the earliest next event** and recomputes after every fire, edit, boot or time change. Events include timed fires, window starts and ends, and snooze expiries.
- Recomputing must handle **"now" falling inside a window**, and post that window's notification immediately.
- Done and snooze state are stored in Room, not only in alarms, so they survive reboots.
- Local IDs are client-generated UUID strings (offline-first sync). Entities carry `ownerMemberId`, `updatedAt` and a soft-delete flag. Settings are keyed by member ID. Phase 1 uses one default local member until login exists (Phase 2).
- Notification channels can't change their sound after creation. There are six channels, {timed, persistent} x {sound, vibrate, silent}, and the member's setting picks which one to use.
- Room schemas are exported to `android/app/schemas/` and committed. Every Room version bump needs a tested migration, so nobody loses reminders.
- **Samsung:** One UI can put sideloaded apps to sleep, which blocks alarms. The app offers a button to request ignoring battery optimizations. Each phone should also add the app to *Settings → Battery → Background usage limits → Never sleeping apps*.
- Notifications fire only for reminders that the profile currently selected on that phone owns or has accepted.

### Chores (Phase 3)

- The server is the source of truth for chores, assignments and completions. The app keeps a **read-only cache** in Room so the timetable and chore reminders work offline. Adding, editing, deleting and marking done need a network until Phase 4.
- The app refreshes the cache when it opens, when the Chores tab opens, after every chore write, and every 15 minutes in the background with WorkManager. **Alarms never wait for the network.** They use whatever is cached.
- A chore reminder stops for the rest of the day once the chore counts as done **for the picked profile**: for ANY, once anyone has done it; for EACH, once that member has done their own part. It starts again the next day. `ReminderScheduler` takes the cached chore done state as an input, so this stays pure and unit-tested.
- The editor offers a chore only if the picked profile is in its cached assignees. This is checked locally because reminders don't reach the server until Phase 4.
- The **Done** button on a persistent chore reminder's notification also marks the chore done for the picked profile (ANY: for everyone; EACH: their part). Offline, it only hides the reminder.
- After a refresh, the app deletes the picked profile's chore reminders for chores it is no longer assigned to, and for deleted chores.
- "Own part" means the profile picked on that phone. Profiles are honor-system, like the rest of login.

## Server rules

- Store the household code, admin PIN and device tokens **only as hashes**, never in plain text. Never log them. The code and PIN use PBKDF2 (slow); device tokens are 32 random bytes and use SHA-256.
- Secrets travel in the JSON body or headers, **never in URLs** (URLs end up in logs).
- Every API route except `GET /health`, `GET /status`, `POST /household` (needs `SETUP_TOKEN`) and `POST /join` requires a valid device token (`Authorization: Bearer ...`).
- Member changes also need the `X-Admin-Pin` header, checked on the server.
- Failed code/PIN/setup-token attempts are counted globally (all traffic comes through Caddy, so per-IP limits don't work). Too many failures lock those endpoints for a while.
- A `401` tells the app its token is no longer valid, and the app returns to the join screen.
- Chore routes need only the device token. Chore IDs are client-generated UUIDs. Validate a non-empty title, weekdays 1..127, at least one assignee, and that every assignee is an existing member who isn't deleted. For EACH, only an assignee can have a completion. For ANY, allow at most one completion per day (checked in the API, because the type lives on `chore`). Today's date comes from `household.timezone`, never from UTC `CURRENT_DATE`.
- Deleting a member is a soft delete, so `ON DELETE CASCADE` doesn't fire. Member deletion must also soft-delete that member's chore assignments.
- Test against a throwaway stack, never the production database: a test household there would block the real setup.
- Schema changes go through **Flyway migrations** in `server/src/main/resources/db/migration` (`V<n>__description.sql`). Never edit a migration that has already been deployed.
- Configuration comes from environment variables (see `deploy/.env.example`). `.env` is gitignored and must never be committed.

## Deployment notes (Oracle Cloud)

- **The API must not be reachable over plain HTTP from the internet.** The household code is the only secret, and HTTP would leak it. Only Caddy publishes ports (80/443). Postgres and the API are not published.
- The VM runs **Oracle Linux 9 (arm64)**, user `opc`. The code lives in `~/everyday-app` and is copied there with rsync from a dev machine (no GitHub credentials on the server). `deploy/.env` exists only on the server.
- The VM also runs other services. Never stop, restart or change firewall rules for anything else on the VM without asking.
- `V1__initial_schema.sql` and `V2__uuid_sync_schema.sql` **have been deployed** (2026-10-08). Schema changes from now on need a new migration file (Phase 3 adds `V3`).
- Oracle blocks ports in **two places**: the VCN security list / NSG in the console, and the VM's **firewalld**. Open 80 and 443 in both.
- **`firewall-cmd --reload` wipes Docker's NAT rules** on this VM. Symptoms: containers get "network is unreachable", and published ports time out from outside. After any firewalld reload, run `docker compose down && docker compose up -d` in `~/everyday-app/deploy` (or `sudo systemctl restart docker`).
- Public endpoint: `https://everyday-home.duckdns.org` (DuckDNS → the VM's public IP). If the VM's public IP ever changes, update it at duckdns.org.
- The VM is arm64, so images must be built for `linux/arm64`. Building on the VM itself is simplest.
- Back up Postgres with `pg_dump` on a schedule (roadmap).

## Build and run

Toolchain: Gradle 9.8.1 (via the wrapper) runs on any full JDK from 17 to 27. A JRE is not enough. Code is compiled for Java 21 through Gradle toolchains with the foojay resolver, which downloads a JDK 21 automatically if one isn't installed.

```sh
# Server
cd server && ./gradlew build          # compile + tests
cd server && ./gradlew run            # needs DATABASE_URL etc. (see deploy/.env.example)

# Android (needs the Android SDK, e.g. from Android Studio; set sdk.dir in android/local.properties)
cd android && ./gradlew assembleDebug

# Emulator for testing: run headless so it does not cover the terminal
~/Android/Sdk/emulator/emulator -avd Medium_Phone_API_37.0 -no-window -no-snapshot-save -no-audio -no-boot-anim &

# Room migration test (needs a running emulator)
cd android && ./gradlew connectedDebugAndroidTest

# End-to-end test against the throwaway stack on the VM (never production)
rsync -az --exclude build --exclude .gradle --exclude .env server deploy oracle:~/everyday-app/
ssh oracle 'cd ~/everyday-app/deploy && docker compose -p everyday-test -f docker-compose.yml -f docker-compose.test.yml up -d --build db api'
ssh -f -N -L 18080:127.0.0.1:18080 oracle      # tunnel to the test API
adb reverse tcp:18080 tcp:18080                # debug builds allow cleartext only to 127.0.0.1/localhost
# In the app: server address http://127.0.0.1:18080, setup code test-setup-code

# Full stack on the VM
cd deploy && cp .env.example .env    # then fill in values
docker compose up -d --build
```

Versions are pinned in each build's `gradle/libs.versions.toml`.

## Conventions

- Kotlin style: official Kotlin coding conventions.
- Commit messages: Conventional Commits (`feat:`, `fix:`, `docs:`, ...).
- Keep `ROADMAP.md` up to date when a feature ships or a plan changes.
