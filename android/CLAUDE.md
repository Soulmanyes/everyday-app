# Android app

Android-specific guidance. Loads when working under `android/`; the shared sync protocol is in the root `CLAUDE.md`.

## Android rules

- **Reminders must fire with no network.** Reminders and their times live in a local Room database and are scheduled on the device with `AlarmManager`. The server is for sync, not for triggering notifications.
- Use exact alarms (`setExactAndAllowWhileIdle`). The manifest declares `USE_EXACT_ALARM` (API 33+) and `SCHEDULE_EXACT_ALARM` with `maxSdkVersion=32`. Still check `canScheduleExactAlarms()`, and fall back to an inexact alarm if it returns false.
- Request `POST_NOTIFICATIONS` at runtime (Android 13+).
- Reschedule all alarms on `BOOT_COMPLETED`, `TIME_SET`, `TIMEZONE_CHANGED`, and app update (`MY_PACKAGE_REPLACED`).
- Scheduling lives in one pure, unit-tested function (`ReminderScheduler`). Given the reminders, done/snooze state and "now", it returns what should be showing now and the next event time. The app keeps **one exact alarm for the earliest next event** and recomputes after every fire, edit, boot or time change. Events include timed fires, window starts and ends, and snooze expiries.
- Recomputing must handle **"now" falling inside a window**, and post that window's notification immediately.
- Done and snooze state are stored in Room, not only in alarms, so they survive reboots.
- Local IDs are client-generated UUID strings (offline-first sync). Entities carry `ownerMemberId`, `updatedAt` and a soft-delete flag. Settings are keyed by member ID. Phase 1 uses one default local member until login exists (Phase 2).
- **All persistent reminders showing at once share one ongoing notification** that lists them, with a Done button for each (up to 3; the rest are marked done in the app). Android 16 bundles an app's separate notifications into one collapsed block, even with a group per reminder (tried on the Phone, 2026-10-08), so separate notifications would hide all but one. The combined notification alerts only when a reminder newly appears in it.
- Notification channels can't change their sound after creation. There is one channel per {timed, persistent} x {sound, vibrate, sound and vibrate, silent}, and the member's setting picks which one to use. Channel ids carry a version (`_v2`): a channel whose behaviour changes needs a new id, because recreating a deleted channel under its old id brings back its old settings. The 1.1.0 ids are deleted.
- Room schemas are exported to `android/app/schemas/` and committed. Every Room version bump needs a tested migration, so nobody loses reminders. Room 5 -> 6 (Phase 6) turns general reminders into events exactly like the server's V7 (`EventConversion`, ids derived as md5 UUIDs, tested against the same values on both sides), and carries over unsynced 1.1.0 deletes and declines as dirty rows.
- **Samsung:** One UI can put sideloaded apps to sleep, which blocks alarms. The app offers a button to request ignoring battery optimizations. Each phone should also add the app to *Settings → Battery → Background usage limits → Never sleeping apps*.
- Notifications fire only for the picked profile's own reminders, on chores and on events it's still on.

### Chores (Phase 3)

- The server is the source of truth for chores, assignments and completions. The app keeps a **read-only cache** in Room so the timetable and chore reminders work offline. Adding, editing, deleting and marking done need a network until Phase 4.
- The app refreshes the cache when it opens, when the Chores tab opens, after every chore write, and every 15 minutes in the background with WorkManager. **Alarms never wait for the network.** They use whatever is cached.
- A chore reminder stops for the rest of the day once the chore counts as done **for the picked profile**: for ANY, once anyone has done it; for EACH, once that member has done their own part. It starts again the next day. `ReminderScheduler` takes the cached chore done state as an input, so this stays pure and unit-tested.
- The editor offers a chore only if the picked profile is in its cached assignees. This is checked locally because reminders don't reach the server until Phase 4.
- The **Done** button on a persistent chore reminder's notification also marks the chore done for the picked profile (ANY: for everyone; EACH: their part). Offline, it only hides the reminder.
- After a refresh, the app deletes the picked profile's chore reminders for chores it is no longer assigned to, and for deleted chores.
- **Chore times**: an assignee adds times straight from the chore sheet. Each is a time on that member's first timed chore reminder for the chore (created if needed; removing the last time deletes it). The server adds every assignee's active chore reminder times and windows to the chores snapshot (`slots`), so the timetable shows when each person is reminded. The picked profile's own slots come from its local reminders so they show at once.
- "Own part" means the member the phone is approved as; the server checks it against the device.
