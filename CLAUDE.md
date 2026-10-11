# CLAUDE.md

Guidance for Claude Code (and humans) working in this repository.

## What this is

**Everyday App** is a private Android app for households (one per family; since 2.0 the server holds several, see "Multiple households"). It has two core features:

1. **Events timetable** (the Events tab): events, weekly or on a date, personal or shared with chosen members. Each person adds their own reminders to an event.
2. **Chore timetable**: a household-wide weekly schedule of chores, with members linked to each chore.

A small backend on the owner's Oracle Cloud VM stores and syncs the data between the household's phones.

See `ROADMAP.md` for planned features and their order.

## Working with the owner

- The owner wants to be **asked when a decision comes up**. Don't silently pick product behaviour, data model changes, new dependencies, or anything that touches the server. Ask first.
- When asking multiple-choice questions, always include a "Discuss this first" option.

## Repository layout

`android/` and `server/` are **separate Gradle builds on purpose**. Configuring an Android Gradle build requires the Android SDK, and the server image is built on the VM, where the SDK isn't installed. Don't merge them into one root build. If shared models are needed later, see the "Shared models" item in `ROADMAP.md`.

## Decisions made (don't change without asking)

| Topic | Decision |
|---|---|
| App | Native Android, Kotlin, Jetpack Compose, Material 3. `minSdk 28` (Android 9): the oldest household phone runs Android 9. |
| Users | Several households on one server, each with several members (2.0, Phase 9). A phone can be in several households. |
| Login | **Join code + name, approved by the admin.** A phone enters the household's server-generated join code and the person's name, and receives a device token in a **pending** state. The admin approves the request (with the admin PIN), either as a new member with that name or **linked to an existing member** (new phone, reinstall, or someone the admin added by hand). The approved phone is **locked to that member**: there is no profile switching. The admin can also reject a request. |
| Household setup | A phone creates a household (name, admin name, admin PIN) with an **invite** from another household's admin (or the server's `SETUP_TOKEN`, the owner's own way in). The server generates the join code and shows it once. |
| Members | Only the **admin** (the household's creator) adds, renames or removes members, and every such request must include the **admin PIN**, which the server checks. The admin can't be removed. |
| Joining | Reminders made on a phone before it joined move to the member the phone is approved as. |
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
- **Event** (Phase 6): something on the timetable, owned by one member: title, optional notes, and either **weekly** on chosen weekdays or **once on a date**, with a start time (none = all day) and an optional end time. It shows without notifying. It's **personal**, or **shared with chosen members** (`event_member`), who see it on their timetable at once (no invite to accept) and are told once ("Sam added you to …", with **Decline**). Only the owner edits or deletes it. A member can **leave** (or decline), which removes them and their reminders on it; the owner can add them again (a stale member list from the owner's phone can't). A removed member's shared events live on, read-only, until every member has left; then they are deleted.
- **Busy** (Phase 7): a **personal** event can show to the other members as "Busy" (`event.show_busy`, default true, chosen in the editor as "Others see: Busy | Nothing"). The snapshot's `busy` list carries other members' personal Busy events with **days and times only**: the server never sends their title or notes. Shared events never show as Busy (their members see them in full, nobody else sees them); one whose members have all left counts as personal again. On the phone Busy events live in their own Room table (`busy_event`, keyed by viewer, replaced on every sync), so alarms and the week view never see them; only the month view shows them, in the owner's member colour.
- **Reminder**: owned by one member, on an **event** (`event_id`) or a **chore** (`chore_id`), never neither. Each person sees and edits **only their own** reminders, and pauses only their own. It's either:
  - **timed**: a normal notification at each time, with a **Snooze** action that uses the member's snooze length. On an event the times are fixed clock times, or **minutes before the start** (`reminder_time.relative`, only when the event has a start), which move with the event and can fall on the day before.
  - **persistent**: an ongoing notification that can't be swiped away (on Android 14+ the system allows swiping, so the app re-posts it while it should show), with a **Done** action that hides it until the next day; Done can also be set and undone in the app. On an event it shows **all day** (`lead_minutes` null) or **from X minutes before the start until the end** (until the start if there's no end). On a chore it has one or more time windows (end after start, no crossing midnight, no overlaps), or "All day".
  - An event reminder takes its title and days from the event. A chore reminder keeps its own message and days (copied from the chore), and only members assigned to that chore can have one.
- Per person per event there is at most one timed and one persistent reminder.
- **Removed members** (admin removes a member): their reminders are deleted, they're taken off others' events, and their events are deleted except shared ones members are still on.
- **Done/snooze state** is per (reminder, member), so it survives reboots and syncs.
- **Join request**: a device in the `pending` state with the name typed on the phone. Pending devices can only read their own status and leave. The admin's phone notifies on each new request (on its next refresh) and Settings shows the count. The admin keeps **Add member** for people without a phone (e.g. a child assigned chores); a phone can be linked to them later through a request.
- **Member settings** (per member, edited in the app): snooze length in minutes, and **Sound / Vibrate / Both (sound and vibrate) / Silent** chosen separately for timed and persistent reminders. Sound is sound only (Phase 6); new members start on Both for timed and Silent for persistent.

Weekdays are stored as a 7-bit mask (bit 0 = Monday through bit 6 = Sunday). Events repeat **weekly** or happen **once on a date**; chores repeat weekly. Times of day are local wall-clock times in the household timezone.

The Events tab is a **weekly timetable** (7 day columns, with previous/next week and a day view) of events and the profile's chore reminders, not a list. An event shows a bell when the profile has active reminders on it.

The Events tab also has a **month view** (Phase 7, Week | Month switch): a month grid of the events the profile sees and **all household chores**, filtered by member (everyone, or per member their events and their chores, each on or off; month only, per phone in `AppPrefs`, as is the Week/Month choice). Cells list events (and other members' Busy events, in the owner's colour, filtered by owner) before chores. Tapping a day opens a day sheet; the week and day views still show only the profile's own events and chore reminders. Grid logic is pure and unit-tested (`ui/calendar/MonthGrid.kt`).

The **Reminders** tab (Phase 6.5) is a plain list of the profile's own reminders, in two sections (Events, then Chores), A to Z by title, one row per reminder. Each row shows what it's on, when that happens, its times or windows, and a switch to pause or resume it. Tapping an event reminder opens its event's sheet (where event reminders are edited); tapping a chore reminder opens its editor. Tabs: Events, Chores, Reminders.

Chore reminders **look like chores**: the fixed chore green and the Chores tab's house icon (`ui/Kinds.kt`), so users can tell them from events. Member dots use the member's **household-wide colour** from the server (`member.color`, a palette key; `ui/MemberColors.kt` maps keys to colours). The server gives each new member a free colour, keeps colours unique per household, and members change their own in Settings (no PIN) or the admin in Manage members. 12 colours, no greens (chore cards are green).

The app is **dark by default**; "Follow phone theme" in Settings (per phone, `AppPrefs`) switches to the phone's light/dark setting.

## Multiple households (Phase 9, 2.0.0)

Decisions and reasoning: `docs/ideas/multi-household.md`.

- **Invites**: any admin makes one-time invites (Manage members, admin PIN): `NEW-XXXX-XXXX-XXXX`, stored as SHA-256, 7 days, **3 slots per household** (a cancelled or expired unused one frees its slot); the protected household (the owner's) has no limit and is shown 99 left. The new household's `invited_by` is the inviting household.
- **Join codes**: `XXXX-XXXX-XXXX` (alphabet without 0, O, 1, I): the first 4 characters are the public `join_id`, the rest the secret, PBKDF2-hashed like before. Shown only when made; the admin can make a new one (the old one stops working). Households from before 2.0 have none until the admin makes one. Wrong codes and admin PINs lock **per household** (`AttemptLimiter` keys).
- **Join links**: shared as `https://<server>/download/join.html#CODE` (the code stays in the fragment, never in server logs), which opens `everyday://join?code=...` (`everyday-dev` for the debug app).
- **Phones in several households**: `Session` keeps the picked household in the 1.x keys plus `other_households`; each `Membership` has its token, member and a notifications switch. With more than one, the app starts on a **household picker**. Screens act on the picked household; refresh, chores, sync, pushes and alarms cover every household (alarms only where notifications are on, titled with the household name). Room 8 adds `householdId` to cached members and chores. A sync merges only its own household's rows (`SyncMerge.inHousehold`), so it never removes another household's data from the phone.
- **Deleting**: an admin deletes the household (`DELETE /household`, admin PIN, typed name); everything cascades, its other phones are pushed and land on the welcome screen.
- **Cleanup**: daily, a household no phone has used for a year is marked and deleted 30 days later unless a phone comes back. `household.protected` (the owner's own household) is never deleted.
- **Owner tool** (no names): `docker compose exec api java -cp app.jar app.everyday.server.OwnerToolKt list` / `remove <id>` (removes it and the households it invited; refuses protected ones).
- Apps before 2.0 get `410` "update the app" from `POST /join` and `POST /household`; joined 1.x phones keep working.

## Sync (Phase 4, events in Phase 6)

- Room stays the source for alarms; the server is a sync target. Each sync (`POST /v2/sync`) is **one round trip for the picked profile**: the phone pushes its dirty rows, the server applies them, and the server returns a **full snapshot** of everything that member can see (events they own, with tombstones, and events shared with them, with members; their own reminders, done/snooze state and settings). No "since" cursor: commit order and timestamp order can differ.
- What syncs: events with their members, the events the member left, reminders with their times and windows, done/snooze state, member settings. Rows of `LOCAL_MEMBER_ID` never sync.
- `showBusy` is optional in a pushed event: phones before 1.4.0 don't send it, and the server then keeps the stored value (Busy for a new event). Old phones ignore the snapshot's `busy` key.
- The 1.1.0 `POST /sync` answers `410 Gone` ("update the app"), never `401`, which would send old phones back to the join screen.
- **Last write wins** on `updatedAt`, using the phone's clock in epoch millis. The server stores the client's value, not `now()`.
- Deletes travel as **tombstones** (`deleted = true`). A row of mine missing from the snapshot never deletes a local row. Events of others that are missing (I'm no longer on them) are removed from the phone, and so is any reminder that isn't mine.
- After a push, a row's dirty flag is cleared **only if its `updatedAt` still equals the pushed value**, so edits made during a sync aren't lost.
- The server checks each row on its own and **rejects only that row** (returned as rejected ids). The phone then drops its dirty flag and keeps the server's version, so one bad row can't block sync forever.
- Merging a snapshot into Room is one pure, unit-tested function.
- Sync runs when the app opens, after local edits, and in the same 15-minute WorkManager job as chores (keep the `ChoreRefreshWorker` class name: installed phones have it enqueued by name).
- **Instant updates** (Phase 8.5, FCM): after a change is committed, the server sends a **data-only** "sync now" push (`data.type = "sync"`, no household data) and the phone runs the same refresh as the 15-minute job (`EverydayApplication.refreshFromServer`), giving up after 15 s and leaving it to a one-off WorkManager job (`sync_now`). Who is pushed: every other approved phone in the household (not the phone that made the change, but the same member's other phones) for chore writes, member changes, colours and syncs; the admin's phones for a new join request; the phone itself when its request is approved or rejected, or its member removed. A sync pushes **only if it wrote something** (`SyncStore.Result.Ok.changed`), so syncs started by pushes or the 15-minute job never push again. Phones register their FCM token with `PUT /me/push-token` after joining (pending phones too) and whenever FCM gives a new one (`PushRegistration`).
- Being added to a shared event posts a notification once ("Sam added you to …", with Decline). Announced event ids are remembered so a later sync doesn't repeat it; the first sync after updating takes the events already shared as announced.

## Server rules

- Store the household code, admin PIN and device tokens **only as hashes**, never in plain text. Never log them. The code and PIN use PBKDF2 (slow); device tokens are 32 random bytes and use SHA-256.
- Secrets travel in the JSON body or headers, **never in URLs** (URLs end up in logs).
- Every API route except `GET /health`, `GET /status`, `POST /household` (needs an invite or `SETUP_TOKEN`) and `POST /join` requires a valid device token (`Authorization: Bearer ...`). Except `GET /me`, `PUT /me/push-token` and `DELETE /device`, it also requires the device to be **approved**, and member-scoped requests (sync, chore done/undo) act **as the device's member**: a different `memberId` is refused.
- Removing a member revokes their devices, which return to the join screen. A revoked device keeps no push token.
- **FCM**: the server signs in with a Firebase service-account key (`FCM_CREDENTIALS`, see "Deployment notes"); without it pushes are off and everything else works. FCM push tokens are never logged. A token FCM reports as `UNREGISTERED` is cleared; other FCM errors keep it.
- Member changes also need the `X-Admin-Pin` header, checked on the server.
- Failed join code and admin PIN attempts are counted per household, failed setup codes, invites and unknown join ids globally (the API sees every request coming from Caddy). Too many failures lock them for a while.
- **Rate limits per client IP** (Ktor `RateLimit`, `RateLimits.kt`): 10/min on `/household` and `/join`, 30/min on admin-PIN routes, 300/min on all requests. The IP is the first `X-Forwarded-For` address: Caddy replaces any value the client sends, and the API port isn't published, so it can be trusted. A limited request gets `429` with an `error` message, which the app shows like any other error (never the `401` path).
- A `401` tells the app its token is no longer valid, and the app returns to the join screen.
- Chore routes need only the device token. Chore IDs are client-generated UUIDs. Validate a non-empty title, weekdays 1..127, at least one assignee, and that every assignee is an existing member who isn't deleted. For EACH, only an assignee can have a completion. For ANY, allow at most one completion per day (checked in the API, because the type lives on `chore`). Today's date comes from `household.timezone`, never from UTC `CURRENT_DATE`.
- Deleting a member is a soft delete, so `ON DELETE CASCADE` doesn't fire. Member deletion must also soft-delete that member's chore assignments, their reminders, and their `event_member` rows, then delete their events nobody is on any more.
- Sync rules: the request's `memberId` must be an active member. Only the owner writes an event and its member list; a member only removes themselves (leave). Only the owner writes a reminder and its times/windows; it needs an event or a chore. Event reminders need an event the owner sees; relative times and leads need the event to have a start. Chore reminders need the owner assigned to the chore. State rows only for the caller's own reminders. Removing a member from an event, leaving, or deleting the event also deletes the affected reminders on it. Times and windows use the same validation as the app.
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
- **Firebase key** (instant updates): the service-account key lives in `~/secrets/firebase-service-account.json` on the VM (folder mode 700, file 644 so the container's user can read it), outside `~/everyday-app`, and in `~/.everyday/` on the owner's PC. Compose mounts it read-only into the API; if it's missing the API container doesn't start. Firebase project `everyday-app-a684a`, with both app ids; the app's `android/app/google-services.json` is committed (it holds no secret).
- **Backups**: cron on the VM runs `deploy/backup.sh` daily at 03:30 UTC (keeps 30 daily + 12 monthly dumps in `~/backups/{daily,monthly}`, log in `~/backups/backup.log`). On the owner's PC, the systemd user timer `everyday-backup-pull.timer` (units in `deploy/pc/`) runs `deploy/pull-backups.sh` daily and copies them to `~/everyday-backups/vm`. A restore was tested on 2026-10-09 by loading a dump into a throwaway `postgres:17` container.

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
# Uses its own copy of the code (~/everyday-test-src, with its own throwaway .env), never production's ~/everyday-app
rsync -az --exclude build --exclude .gradle --exclude .env server deploy oracle:~/everyday-test-src/
ssh oracle 'cd ~/everyday-test-src/deploy && docker compose -p everyday-test -f docker-compose.yml -f docker-compose.test.yml up -d --build db api'
ssh -f -N -L 18080:127.0.0.1:18080 oracle      # tunnel to the test API
adb reverse tcp:18080 tcp:18080                # debug builds allow cleartext only to 127.0.0.1/localhost
# In the app: server address http://127.0.0.1:18080, setup code test-setup-code

# Full stack on the VM
cd deploy && cp .env.example .env    # then fill in values
docker compose up -d --build
```

**CI** (`.github/workflows/ci.yml`): on pushes to `dev` and `main` and on PRs, GitHub Actions builds and tests the server (`./gradlew build`) and the app (`assembleDebug testDebugUnitTest`, debug APK kept 7 days as an artifact). The Room migration test needs an emulator and isn't run in CI. **Dependabot** (`.github/dependabot.yml`) opens weekly PRs into `dev` for the Gradle catalogs and the workflow's actions; it skips `kotlinx-serialization` (pinned for room-testing) and Postgres 18 test binaries (production runs 17).

## Releases and the dev app

- **Two apps.** The **release** build (`app.everyday.android`, "Everyday") is what the household installs from `https://everyday-home.duckdns.org/download/`. The **debug** build (`app.everyday.android.debug`, "Everyday (dev)") installs next to it, defaults to the **test server** (`http://127.0.0.1:18080` through `adb reverse` and the SSH tunnel), and never checks for updates. New features are tried in the dev app first, never on real data.
- **Branches.** Work happens on `dev`. A release merges `dev` into `main`, bumps `versionCode` and `versionName` in `android/app/build.gradle.kts`, adds that versionCode's notes to `RELEASE_NOTES` (`ui/whatsnew/ReleaseNotes.kt`, shown once after the update and in Settings → What's new), commits, and runs `deploy/publish-release.sh` (from `main`, clean tree). It builds the signed APK, uploads it with `version.json`, and tags `v<versionName>`. Server changes deploy before the release that needs them.
- **Updates.** Release builds fetch `/download/version.json` when opened and show "Version X is available" if its `versionCode` is higher.
- **Signing key.** `~/.everyday/everyday-release.jks` with `~/.everyday/keystore.properties` (never in the repo), backed up on the VM in `~/backups/signing/`. **Losing it means nobody can install updates** over the current app (they'd have to uninstall and rejoin). Without the key file, release builds come out unsigned.
- The admin's own new phone joins with **"I'm the household admin"** and the admin PIN, which approves it as the admin at once.

## Conventions

- Commit messages: Conventional Commits (`feat:`, `fix:`, `docs:`, ...).
- Keep `ROADMAP.md` up to date when a feature ships or a plan changes.
