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
Server is the source of truth. The app caches chores read-only, and edits need a network until Phase 4.

- [ ] Server V3 migration: chore completion type (`ANY` / `EACH`), `chore_completion` table (chore, member, day in household timezone)
- [ ] Server: chores and assignments CRUD (any member, no PIN, at least one assignee); mark done / undo; member delete also removes their assignments
- [ ] App: Room migration with tested upgrade for cached chores, assignments and completions
- [ ] App: refresh on open, on the Chores tab, after writes, and every 15 min with WorkManager (new dependency)
- [ ] App: weekly timetable view (columns per weekday, chips per assignee, done state for today)
- [ ] App: add/edit/delete chore, choose weekdays, completion type, assign members
- [ ] App: mark done / undo for today: ANY by anyone; EACH only your own part
- [ ] Chore reminders: assigned members add their own reminders for a chore (any number of times); they stop for the day once the chore is done for that member; deleted when unassigned or the chore is deleted
- No history screen (decided 2026-10-08)

## Phase 4: Sync
- [ ] Server: reminders CRUD scoped to the owning member
- [ ] Shared general reminders: owner invites members; invitees get an in-app invite and opt in/out
- [ ] App: offline-first sync (local Room is the source for alarms, push and pull changes)
- [ ] Conflict rule: last write wins using `updated_at`
- [ ] Background sync with WorkManager

## Later / ideas (to discuss before building)
- [ ] Optional per-member PIN (the admin PIN exists from Phase 2)
- [ ] Chore rotation (assignees rotate each week automatically)
- [ ] Push notification when someone changes the chore timetable (needs FCM or ntfy)
- [ ] Home-screen widget: today's chores and the next reminder
- [ ] Shopping list shared by the household
- [ ] Shared household calendar / events
- [ ] Chore points and a weekly summary
- [ ] Dark mode and theme colour per member
- [ ] Scheduled Postgres backups (`pg_dump` cron with off-VM copy)
- [ ] CI: GitHub Actions building the server and the debug APK
- [ ] Shared models: move API DTOs into a Kotlin Multiplatform `shared` module used by both builds
- [ ] Alternative to a public endpoint: Tailscale, so the API isn't exposed to the internet at all
