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
- [ ] Deploy: `pg_dump` and a copy of the phone's database first

## Since Phase 4
- [x] Chore reminders look like chores (green, house icon); distinct member colours
- [x] Quick chore times on the chore sheet, shown on the timetable for everyone
- [x] Join by name with admin approval (or linking to an existing member); phones locked to their member

## Later / ideas (to discuss before building)
- [ ] Members pick their own **dot colour** (shown on chores and events), from at least 10 colours, with each colour taken by only one member. Notes:
  - **Household-wide, not per phone**: the colour is stored on the server (`member.color` already exists, unused) and sent with the member list, so every phone shows the same colour for the same person. Add a unique index per household for members who aren't deleted.
  - Today the app hands out 8 colours in household order (`MEMBER_COLORS` in `ui/chores/ChoresScreen.kt`), with no greens because chore cards are green. Grow the palette to 10+ colours that stay distinct from that green in light and dark mode.
  - A member sets their own colour in Settings with no PIN; colours already taken show as unavailable. Members who haven't picked (e.g. added by hand) get a free colour **assigned by the server** when they're created, so there are no colours worked out on each phone any more.
- [ ] Reminders tab as a timetable of **events** (shown without notifying), where each person can optionally add their own reminders to an event, like chore times. Notes: [docs/ideas/events-timetable.md](docs/ideas/events-timetable.md)
- [ ] Allow others to create their own households on this server (today: exactly one household, creation disabled once set up). Needs: dropping the singleton, scoping every query and the join code lookup to a household, a way to create one without the server's `SETUP_TOKEN`, and abuse limits. Notes: [docs/ideas/multi-household.md](docs/ideas/multi-household.md)
- [ ] Optional per-member PIN (the admin PIN exists from Phase 2)
- [ ] Chore rotation (assignees rotate each week automatically)
- [ ] Push notification when someone changes the chore timetable (needs FCM or ntfy)
- [ ] Home-screen widget: today's chores and the next reminder
- [ ] Shopping list shared by the household
- [ ] Shared household calendar / events
- [ ] Chore points and a weekly summary
- [ ] Dark theme by default (decided 2026-10-09): the app starts in dark mode, and Settings has a **"Follow phone theme"** checkbox (off by default) that switches to the phone's light/dark setting. Today it always follows the phone (`EverydayTheme` in `ui/Theme.kt`). The choice is per phone, not synced. No per-member theme colours.
- [ ] Scheduled Postgres backups (`pg_dump` cron with off-VM copy)
- [ ] CI: GitHub Actions building the server and the debug APK
- [ ] Shared models: move API DTOs into a Kotlin Multiplatform `shared` module used by both builds
- [ ] Alternative to a public endpoint: Tailscale, so the API isn't exposed to the internet at all
