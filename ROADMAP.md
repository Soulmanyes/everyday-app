# Roadmap

Status: `[ ]` planned · `[~]` in progress · `[x]` done

## Phase 0: Foundations
- [x] Private GitHub repo, `CLAUDE.md`, this roadmap
- [~] Android project skeleton (Compose, Material 3); written, not yet built (needs Android SDK)
- [x] Ktor server skeleton with `/health`, PostgreSQL connection, Flyway V1 schema
- [~] docker-compose stack (Postgres + API + Caddy); written, not yet run
- [ ] Set up a DuckDNS subdomain and point it at the Oracle VM
- [ ] Open ports 80/443 on the Oracle VCN security list and the VM's iptables
- [ ] First deploy to the Oracle VM and confirm `https://<subdomain>/health` works

## Phase 1: Local reminders (works offline, single phone)
- [ ] Room database: reminders, reminder times, weekday mask
- [ ] Create / edit / delete reminder screen: message, weekdays, any number of times
- [ ] AlarmManager scheduling with exact alarms and the permission flow
- [ ] Notification channel and `POST_NOTIFICATIONS` permission
- [ ] Reschedule on boot, time change, timezone change, and app update
- [ ] Pause/resume a reminder without deleting it

## Phase 2: Household and login
- [ ] Server: create a household (one-time setup command) and set the household code
- [ ] Server: `POST /join` with the household code, which returns a device token
- [ ] Server: members CRUD
- [ ] App: join screen (server URL + household code), store the token securely
- [ ] App: profile picker on launch, remember the last profile

## Phase 3: Chore timetable
- [ ] Server: chores and chore assignments CRUD
- [ ] App: weekly timetable view (columns per weekday, chips per assignee)
- [ ] App: add/edit chore, choose weekdays, assign members
- [ ] Chore reminders: assigned members add their own reminders for a chore (any number of times)
- [ ] Mark a chore done for today, with a history of who did what

## Phase 4: Sync
- [ ] Server: reminders CRUD scoped to the owning member
- [ ] Shared general reminders: owner invites members; invitees get an in-app invite and opt in/out
- [ ] App: offline-first sync (local Room is the source for alarms, push and pull changes)
- [ ] Conflict rule: last write wins using `updated_at`
- [ ] Background sync with WorkManager

## Later / ideas (to discuss before building)
- [ ] Optional per-member PIN
- [ ] Chore rotation (assignees rotate each week automatically)
- [ ] Snooze and "done" actions directly on the notification
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
