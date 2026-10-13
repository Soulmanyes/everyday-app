# Roadmap

Status: `[ ]` planned · `[~]` in progress · `[x]` done

## Phase 0: Foundations
- [x] Private GitHub repo, `CLAUDE.md`, this roadmap
- [x] Android project skeleton (Compose, Material 3); debug APK builds and runs on an API 37 emulator
- [x] Ktor server skeleton with `/health`, PostgreSQL connection, Flyway V1 schema
- [x] docker-compose stack (Postgres + API + Caddy) running on the VM
- [x] DuckDNS subdomain `everyday-home.duckdns.org` pointing at the VM
- [x] Grow the VM root filesystem (now 100 GB)
- [x] Open ports 80/443 on the Oracle VCN security list and in firewalld on the VM
- [x] First deploy; `https://everyday-home.duckdns.org/health` returns ok with a Let's Encrypt cert

## Phase 1: Local reminders (works offline, single phone)
Verified on an API 37 emulator on 2026-10-08. Not yet tested on a real Samsung phone.

- [x] Room database (UUID ids, owner, soft delete, schema export): reminders, times, windows, done/snooze state, member settings
- [x] Pure scheduler with unit tests (weekday mask, DST, now-inside-window, done, snooze)
- [x] Timed reminders: notification with Snooze (per-member snooze length)
- [x] Persistent reminders: ongoing notification during time windows or all day; Done hides it until tomorrow; re-posted if swiped (Android 14+)
- [x] One exact alarm for the next event; recompute on fire, edit, boot, time/timezone change, app update
- [x] Notification permission flow and exact-alarm fallback
- [x] Weekly calendar screen (7 day columns), mark done / undo from the calendar
- [x] Create / edit / delete / pause reminder screen
- [x] Settings screen: snooze length, Sound/Vibrate/Silent for timed and persistent, battery optimization button
- [x] Calendar readability: tap a day header for a full-width day view with times on the blocks

## Phase 2: Household and login
Live since 2026-10-08: household created from the owner's phone; setup code disabled afterwards.

- [x] Server V2 migration: rebuild the (empty) V1 tables with UUID ids, sync fields (`updated_at`, `deleted`), reminder modes/windows/state, member settings, admin member + PIN
- [x] Server: `POST /household` (one-time setup token, singleton), `POST /join`, `GET /me`, members CRUD behind the admin PIN, failed-attempt lockout
- [x] App: welcome screen (create household / join / not now), join with household code, create household flow
- [x] App: profile picker, remembered per phone, switch in Settings; existing reminders move to the picked profile
- [x] App: admin member management (PIN), leave household, 401 returns to join
- [x] Production: deploy V2, set `SETUP_TOKEN`, owner creates the household from their phone

## Phase 3: Chore timetable
Built and verified end to end on an API 37 emulator against the test stack on 2026-10-08. Server is the source of truth. The app caches chores read-only, and edits need a network until Phase 4.

- [x] Server V3 migration: chore completion type (`ANY` / `EACH`), `chore_completion` table (chore, member, day in household timezone)
- [x] Server: chores and assignments CRUD (any member, no PIN, at least one assignee); mark done / undo; member delete also removes their assignments
- [x] App: Room migration with tested upgrade for cached chores, assignments and completions
- [x] App: refresh on open, on the Chores tab, after writes, and every 15 min with WorkManager (new dependency)
- [x] App: weekly timetable view (columns per weekday, chips per assignee, done state for today)
- [x] App: add/edit/delete chore, choose weekdays, completion type, assign members
- [x] App: mark done / undo for today: ANY by anyone; EACH only your own part
- [x] Chore reminders: assigned members add their own reminders for a chore (any number of times); they stop for the day once the chore is done for that member; deleted when unassigned or the chore is deleted
- [x] Done on a persistent chore reminder's notification also ticks the chore (decided 2026-10-08)
- No history screen (decided 2026-10-08)

## Phase 4: Sync
Design agreed 2026-10-08 (see CLAUDE.md, "Sync (Phase 4)"). Built and verified end to end on an API 37 emulator against the test stack on 2026-10-09, including an in-place upgrade from the Phase 3 app with existing reminders.

- [x] Server V4 migration: `reminder.owner_receives`, `reminder_recipient.deleted`; store client `updated_at`
- [x] Server: `POST /sync` (push dirty rows, per-row validation and rejection, last write wins, full snapshot back)
- [x] Server: removing a member deletes their unshared reminders and their invites; shared ones live on for accepted invitees until all remove them
- [x] App: Room v3 migration (dirty flags, `reminder_state` keyed by reminder + member, local recipients) with a tested upgrade
- [x] App: pure, unit-tested merge of a snapshot into Room
- [x] App: sync on open, after edits, and every 15 min (same job as chores)
- [x] App: share a general reminder with members; owner sees Pending / Accepted / Declined; "Remind me too"
- [x] App: invites notification and a "Shared with me" list to opt in/out; removed member's reminders can be removed for yourself
- [x] Calendar shows shared reminders I've accepted (read-only, with the owner's name)
- [x] Deploy: `pg_dump` and a copy of the phone's database first

## Since Phase 4
- [x] Chore reminders look like chores (green, house icon); distinct member colours
- [x] Quick chore times on the chore sheet, shown on the timetable for everyone
- [x] Join by name with admin approval (or linking to an existing member); phones locked to their member
- [x] **Release 1.0.0** (2026-10-09): signed release build on https://everyday-home.duckdns.org/download/ with an in-app update prompt; "Everyday (dev)" debug app on the test server; admin rejoin with the admin PIN

## How phases ship

Each phase is built on `dev` and works on its own. When it's done, the owner is asked whether to merge it into `main`. If approved, the version goes up and `deploy/publish-release.sh` publishes it. Phases raise the middle number (Phase 5 = 1.1.0, Phase 6 = 1.2.0, Phase 6.5 = 1.3.0, …), and fixes between phases raise the last (1.1.1).

## Phase 5: Look & feel, and backups (1.1.0)
Built on `dev` and verified on an API 37 emulator against the test stack on 2026-10-09; backups running on the VM and this PC.

- [x] Dark theme by default; Settings checkbox **"Follow phone theme"** (off by default) switches to the phone's light/dark setting. Per phone, not synced. Today the app always follows the phone (`EverydayTheme` in `ui/Theme.kt`).
- [x] Members pick their own **dot colour** (shown on chores, and later events) from at least 10 colours, one member per colour:
  - **Household-wide, not per phone**: stored on the server (`member.color` exists, unused) and sent with the member list; unique per household for members who aren't deleted.
  - The palette grows from 8 (`MEMBER_COLORS` in `ui/chores/ChoresScreen.kt`, no greens because chore cards are green) to 10+ colours that stay distinct from that green in light and dark mode.
  - Set in Settings without a PIN; taken colours show as unavailable. Members who haven't picked (e.g. added by hand) get a free colour **assigned by the server**, so phones no longer work out colours themselves.
- [x] Scheduled database backups (server only): daily `pg_dump`, keeping 30 daily and 12 monthly, plus a copy pulled to the owner's PC daily (decided 2026-10-09). About 4 KB per backup today.

## Phase 6: Events timetable (1.2.0)
Built on `dev` and verified on an API 37 emulator against the test stack on 2026-10-09, including an in-place upgrade from 1.1.0; server V7 rehearsed on a copy of production first.

- [x] Reminders tab becomes a timetable of **events** (shown without notifying); each person can add their own reminders to an event, like chore times. Absorbs the old "shared household calendar / events" idea. Notes and open questions: [docs/ideas/events-timetable.md](docs/ideas/events-timetable.md)
- [x] Notification sound setting gets a **"Sound and vibrate"** option next to sound, vibrate and silent, for timed and persistent reminders. **"Sound" becomes sound only** (decided 2026-10-09): today its channel also asks for vibration (`reminders/Notifier.kt`). Channels can't change after they are created, so both need new channels.

## Phase 6.5: Events and Reminders tabs (1.3.0)
Clarifies Phase 6 (decided 2026-10-09). App only: no server or schema change. Built on `dev` and checked on an API 37 emulator on 2026-10-09.

- [x] Rename the **Reminders** tab to **Events**. It stays the events timetable built in Phase 6.
- [x] New **Reminders** tab: a plain list of all my reminders, on events and on chores. Each row shows what it's on, its times or windows, and whether it's on or paused; tapping it opens it for editing (an event reminder opens its event's sheet, a chore reminder its editor). Two sections, Events then Chores, A to Z, with a pause switch on each row; tabs are Events, Chores, Reminders (decided 2026-10-09).

## Phase 7: Monthly view (1.4.0)
Decided 2026-10-09. Replaces the old Phase 7 (chore rotation and points, now dropped). Built on `dev` and checked on an API 37 emulator against the test stack on 2026-10-10, including an in-place upgrade from Room 6. **Server V8 must be deployed before 1.4.0 is released.**

- [x] New **monthly view**: a **Week | Month** switch on the Events tab. The month grid lists up to 3 items a day (events first, then chores) and "+N"; tapping a day opens a sheet listing it, where each item opens its event or chore sheet. The week and day views stay "just me" (decided 2026-10-09).
- [x] Choose whose chores and events show: **everyone**, or per member their **events** and their **chores**, each switched on or off (decided 2026-10-10). Month view only, remembered on this phone, like Week/Month. A chore shows if any assignee has chores on, an event (or Busy event) if its owner or anyone it's shared with has events on.
- [x] Personal events can show to other members as **"Busy"** (times only, no title or notes) or not at all. The owner chooses per event in the editor ("Others see: Busy | Nothing"); the default is **Busy**, for new and existing personal events. Server V8 adds `event.show_busy` and a `busy` list to the sync snapshot; Room 7 adds `busy_event`. Busy shows on the month view only, in the owner's colour. Events shared between other members never show as Busy (decided 2026-10-09).
- [x] Events shared with me show in full, as on the Events tab.

## Phase 8: What's new (1.5.0)
App only: no server or schema change. Built on `dev` and checked on an API 37 emulator on 2026-10-11 (fresh install, update from 1.4.0, dismiss, Settings list).

- [x] After the app updates, show a popup once listing what changed in the new version. Notes are bundled in the app (`ui/whatsnew/ReleaseNotes.kt`, one entry per versionCode); a phone that skipped versions sees every one it missed; a fresh install shows nothing. Settings → About → **What's new** lists every release back to 1.1.0 (decided 2026-10-10).

## Phase 8.5: Instant updates (1.6.0)
Moved out of Phase 8 on 2026-10-10 until the owner set up the Firebase project (done 2026-10-11, project `everyday-app-a684a`). Server (V9) deployed and checked on the test stack with the dev app on an API 37 emulator on 2026-10-11 (app in the background and with its process killed: the change arrived in under a second).

- [x] Push a notification to phones when something changes (chores, shared reminders, join requests) instead of waiting for the 15-minute refresh. Uses **FCM** (decided 2026-10-10): a data-only "sync now" push, so no household data goes through Google; the 15-minute refresh stays as a fallback. Needs a Firebase project with both app IDs, `google-services.json` in the app, and a service-account key on the VM. Every other phone is pushed (decided 2026-10-11); sends use google-auth-library and the FCM HTTP v1 API, not the Firebase Admin SDK (decided 2026-10-11).

## Phase 9: Multiple households (2.0.0)
Built on `dev` 2026-10-11 and checked on an API 37 emulator against the test stack (update from 1.6.0 keeps the phone joined; a phone in three households; invite, join code, join link; notification Done in a household that isn't picked). Released 2026-10-11.

- [x] Others create their own households on this server, through invites from household admins (3 each, 7 days). Notes and decisions: [docs/ideas/multi-household.md](docs/ideas/multi-household.md)
- [x] Generated join codes (shown once, new one on demand) and join links
- [x] A phone in several households: household picker at start, per-household notification switch
- [x] Admin deletes the household; unused households are cleaned up after a year; owner tool without names; privacy note
- [x] Server hardening for a public, multi-household server (instead of Tailscale, decided 2026-10-09): wrong join codes and admin PINs lock per household (2026-10-11); malformed codes, unknown join ids, invites and setup codes still share one global lock, which per-IP rate limits back up; dependencies kept up to date by Dependabot. Per-IP rate limiting in the API was added early (2026-10-09, see "Developer and ops").

## Phase 9.5: Easier inviting into your household (2.1.0)
Planned 2026-10-11, first in the queue. Released 2026-10-12 as 2.1.0.
- [x] **One-tap invite links** (decided 2026-10-11): Settings → **Invite someone** (admin, with the PIN) makes a one-time member invite link, valid 7 days, and opens the share sheet. No need to find or remake the join code, which can only be shown once. The person taps the link (installing the app first if needed), enters their name, and the admin approves them as today.
- [x] The invite is stored hashed and used up when someone joins with it; the admin sees open invites and can cancel them. The household join code keeps working as well.

## Phase 9.6: Multi-day events (2.2.0)
Planned 2026-10-11. Released 2026-10-12 as 2.2.0.

Decided 2026-10-12:
- **Setting the end**: a one-off event picks an end **date** and time, as in a calendar app. A weekly one picks the **weekday it ends on** (starts every Fri 18:00, ends Sun 20:00); a weekly multi-day event starts on **one weekday only**. Stored as "ends N days after it starts" (0 = the same day, as today).
- **Length**: up to **31 days** for a one-off event; a weekly one ends within 6 days of its start.
- **Persistent reminders**: the person chooses **Whole event** (from X minutes before the start, or all day from the first day, until the end, across days) or **Each day** (all day on every day of the event). Done hides it until the next day, as today.
- **Timed reminders** at fixed clock times fire on **every day** of the event; "X minutes before the start" fires once.
- **Phones on 2.1** show a multi-day event on its first day only, until midnight; nobody is forced to update. Editing it from a 2.1 phone keeps its end day (the server keeps what an old phone doesn't send, as with Busy).
- [x] Events can span several days, both **one-off** (a holiday, Fri 18:00 to Sun 20:00 on chosen dates) and **weekly** (every weekend, Fri 18:00 to Sun 20:00). Today an event sits within one day.
- [x] They show across their days in the week and month views; Busy and shared events work the same way.
- [x] Reminders on them: before the start (as today), and persistent reminders over the whole span or each day.
- [x] Server, sync and Room change (the event stores an end day as well as an end time), with migrations that keep existing events as they are.

## Phase 9.7: Household fixes and calmer views (2.3.0)
Gaps left by 2.0.0 and view changes, planned 2026-10-11. Split from the old Phase 9.7 on 2026-10-12 (the sharing changes became 9.8, personal chores 9.9). Released 2026-10-12 as 2.3.0.
- [x] Tapping a "someone wants to join" notification opens that household (today it opens whichever household is picked).
- [x] Wrong-code lock per sender (decided 2026-10-11): mistyped or unknown join codes, invites and setup codes count per client IP (10 in 15 minutes locks only that address), with a server-wide lock kept only as a backstop against guessing spread over many addresses (about 200 failures in 15 minutes). Wrong join codes and admin PINs for a known household keep their per-household lock.
- [x] The week view shows everything the month view does (decided 2026-10-11): others' Busy events (scheduling-shared ones once Phase 9.8 adds them), with the same member filter, alongside the profile's own events.
- [x] Settings: **Show chores on the Events tab** (decided 2026-10-11, per phone, on by default). On: chores show in the week and month views alike (as in the month view today); the week view shows them in the all-day row with the member filter, plus the profile's own chore reminders at their times (decided 2026-10-12). Off: no chores or chore reminders on the Events tab; they stay on the Chores and Reminders tabs.
- [x] Less cluttered month view (decided 2026-10-11): each day cell shows **one line per person** in their colour with a count ("Sam · 3") instead of every event; tapping the day still opens the day sheet with the full list.
- [x] The month view's member filter starts on **the profile's own events** instead of everyone; others are switched on with the existing filter.

## Phase 9.8: "Everyone" events and sharing for scheduling (2.4.0)
Planned 2026-10-11, split out 2026-10-12. Released 2026-10-12 as 2.4.0 (server V14, Room 10). Decided 2026-10-12: both ways can be mixed on one event (two chip rows, each with Everyone; a person's own choice beats Everyone); someone shared for scheduling can **Hide** it (gone until the owner shares it again); "Others see: Busy | Nothing" now applies to whoever is left out of a shared event too (events shared before stay at Nothing). Events shared with me for scheduling show whatever the member filter, like the events I take part in.
- [x] Events: **Share with: Everyone** (decided 2026-10-11). The event is shared with the whole household, including members who join later: the event stores "everyone" rather than a member list (server and sync change). Members can still leave it.
- [x] Events: two clearly different ways to involve someone (decided 2026-10-11), named so the difference is obvious in the editor:
  - **Add to the event**: they take part, as sharing works today. It's on their timetable, they can set reminders, and they're told "Sam added you to …".
  - **Share for scheduling**: they can see it but aren't taking part. They see the title and times (like Busy, but with the title), with no reminders and no notification. Works with "Everyone" too.
- [x] The week view also shows scheduling-shared events.

## Phase 9.9: Personal chores (2.5.0)
Planned 2026-10-11, split out 2026-10-12. Released 2026-10-13 as 2.5.0 (server V15, Room 11).
- [x] **Personal chores** (decided 2026-10-11): a "Personal (only I see it)" switch in the chore editor, offered only when you're the chore's only assignee **and you created it yourself** (not when someone else assigned it to you). Other members don't see a personal chore at all. The server stores who created each chore (`chore.created_by`).
- [x] Adding another assignee makes a personal chore shared again, also from a 2.4 phone (decided 2026-10-12). Removing a member deletes their personal chores (decided 2026-10-12).
- [x] **The Chores tab shows my chores** (decided 2026-10-12): the chores I'm assigned to and the chores I created for others, not every household chore. Chores from before 2.5 have no creator and count only for their assignees (decided 2026-10-12).
- [x] A **Mine | All** switch on the Chores tab (decided 2026-10-12, per phone, starts on Mine). All shows every chore except other members' personal ones.

## Phase 10: Import from Google Calendar (2.6.0)
Planned 2026-10-11. Released 2026-10-13 as 2.6.0 (server V16, Room 12). Started as app only; the longer "before" reminders (decided 2026-10-13) need a server change.
- [x] One-time import from the **phone's own calendar** (Google Calendar syncs onto the phone; read with `READ_CALENDAR`, no Google sign-in or API), from **Settings → Import from calendar** (decided 2026-10-13). Pick calendars (primary ones ticked), then tick the events of the next 3 months to import (none ticked, with Select all); each becomes one of the profile's own events, shown to others as Busy like any new event (decided 2026-10-13).
- [x] Weekly repeats (every week, no end) and one-off events import as they are. Daily repeats with no end import as an "Every day" weekly event (decided 2026-10-13). Other repeats (with an end date or count, every 2+ weeks or days, monthly, yearly), and weekly ones starting more than a week ahead, become one-off events for the next 3 months. Multi-day events import as multi-day events (Phase 9.6). Google descriptions become notes (HTML removed, up to 500 characters).
- [x] No ongoing sync: later changes in Google Calendar don't follow. Importing the same event again is recognised and skipped: the iCal UID (plus the start, for an occurrence imported as a one-off) is remembered **on this phone only** (Room `imported_event`, per member; decided 2026-10-13). An imported event deleted in Everyday can be imported again.
- [x] **Reminder review** after importing (decided 2026-10-13): a one-time list of the imported events' calendar pop-up reminders, none ticked; ticked ones become Everyday reminders. On timed events they're "X before the start"; on all-day events (Google counts back from midnight, usually to the day before) they become that clock time on the event's day. Reminders over 7 days before are shown greyed out.
- [x] "Before the start" reminders now go up to **7 days** (were under 24 hours; server V16, decided 2026-10-13), and the event sheet offers **1 day** and **2 days before**. The scheduler looks that much further ahead. Phones on 2.5 accept them but may miss ones over a day ahead until they update.

## Phase 11: Better colour picking (2.7.0)
Added 2026-10-12. Not started. Details to decide when it starts.
- [ ] A fuller colour picker for member colours, perhaps any RGB colour (0-255 per channel) instead of the 12 fixed palette colours.
- [ ] To decide then: whether colours must still differ enough between members of a household (today they're unique per household), and whether greens stay out (chore cards are green), since any RGB colour can come close to another one or to the chore green. Server change: `member.color` holds a palette key today.

## Developer and ops (any time, no app release)
- [x] CI: GitHub Actions building the server and the debug APK and running the unit tests (2026-10-09)
- [x] CI: Dependabot flags outdated dependencies with weekly PRs into `dev` (2026-10-09)
- [x] Per-IP rate limits in the API (Ktor `RateLimit`): 10/min for joining and setup, 30/min for admin-PIN routes, 300/min overall (2026-10-09)
- [x] Dependency check (2026-10-09): everything current except WorkManager (2.11.2 to 2.12.0). Kotlin 2.5 is still beta; `kotlinx-serialization` is pinned for room-testing; embedded Postgres stays on 17 to match production
- [ ] Shared models: move API DTOs into a Kotlin Multiplatform `shared` module used by both builds

## Dropped (decided 2026-10-09)
- Chore rotation, chore points and a weekly summary (the old Phase 7): replaced by the monthly view
- Shopping list, home-screen widget: not wanted
- Per-member PIN: each phone already belongs to one approved member
- Tailscale: every member would need to install it; the public server is hardened instead (Phase 9)
