# Idea: a timetable of events, with optional reminders

Status: **decided 2026-10-09, in progress** (Phase 6, 1.2.0). Roadmap entry: Phase 6 in `ROADMAP.md`.

## Goal

Turn the Reminders tab into a **timetable of events**, like the Chores tab:

- An **event** is something that happens at a time, such as "Swimming lesson, Tue 17:00–18:00"
  or "Bins collected, Thu". It shows on the timetable **just for looking at**, with no notification.
- Anyone who wants a nudge **adds reminders to the event**, the same way assignees add their own
  times to a chore today: "Your times" on the event's sheet, with quick times plus "More reminder
  options" for persistent windows.
- So "having something on the calendar" and "being notified about it" become separate things.
  Today every entry in the Reminders tab *is* a notification.

## How it works today (for reference)

- The Reminders tab is a weekly calendar of **reminders**. Each reminder is a message, weekdays,
  and either times (timed) or windows (persistent), and it always notifies its owner unless paused.
- **Chores** already have the pattern this idea copies:
  - The chore is the thing on the timetable (title, days, assignees).
  - Each assignee attaches their own **chore reminders** (`reminder.chore_id`), which notify only
    them and go quiet once the chore is done.
  - Quick times from the chore sheet (`ReminderRepository.addChoreTime` / `removeChoreTime`), and
    the server publishes each assignee's times as `slots` for the timetable.
- **Sharing** works per reminder: the owner invites members, invitees opt in or out, and
  "Remind me too" covers FYI-only reminders.

## Possible shape

- New **event** entity: title, notes, weekdays (or a date, see the open questions), start time and
  optional end time (or all day), owner, and who it's for (only me, chosen members, or everyone).
- **Event reminders**, like chore reminders: `reminder.event_id` (next to `chore_id`). Each person
  attaches their own, so one person can be reminded 30 minutes before and another not at all.
- The timetable shows events as blocks (like today's calendar), plus each person's reminder times,
  like the chore cards do.
- Shared events probably **replace the current invite/opt-in sharing**:
  - Sharing an event means it shows on others' timetables.
  - Each person decides whether to add reminders.
  - "Remind me too" and invite answers may no longer be needed. Decide whether to migrate or keep
    both.
- Event colour and icon: a third kind next to reminders and chores (`ui/Kinds.kt`).

## Decisions (2026-10-09)

1. **Repeating and one-off**: an event is either weekly on chosen weekdays, or once on a date.
2. **Every reminder belongs to an event** (or a chore). No standalone reminders: "take meds 08:00"
   is an event "Meds" with a reminder. An event is either **personal** (only the creator sees it)
   or **shared with chosen members**. Shared events show on their timetables at once: no invite to
   accept, and no "Remind me too". A member can leave (or decline) a shared event, which removes them
   from it; the owner can add them again.
3. **Only the creator** edits or deletes an event (title, days/date, time, who it's shared with).
   Others only add or remove their own reminders on it.
4. **Each person's reminders on an event** can be any mix of:
   - persistent **all day** on the event's day;
   - persistent **from X before the start until the event ends**;
   - timed **X before the start** (X can be 0, "at start");
   - timed at a **fixed clock time**.
   Relative reminders move with the event.
5. **Existing reminders are converted automatically**: each becomes a weekly event with the same
   message and days. Timed times become fixed-time reminders. Each persistent window becomes an
   event with that window as its time and a "from start until end" reminder (several windows give
   one event per window). Shared reminders become events shared with the invitees who accepted,
   each keeping a reminder; pending and declined invites are dropped. Chore reminders stay on chores.
6. **Events are informational**: no done or attendance state. Done on a persistent reminder still
   hides that reminder for the day, as today.

Also in Phase 6: notification setting **Sound / Vibrate / Sound and vibrate / Silent**, where Sound
becomes sound only (new channels, since channels can't change after creation).

## Technical plan (approved 2026-10-09)

Defaults settled with the owner:
1. Existing **Sound** settings become **Sound and vibrate** (today's behaviour); new members start on
   Sound and vibrate for timed reminders, Silent for persistent.
2. A shared event shows **only my own** reminder times.
3. Being added to a shared event posts **one notification** ("Sam added you to Swimming") with a
   **Decline** button. Decline (or "Leave event" in the app) **removes me from the event**; the owner
   sees it and can add me again, which notifies me again (decided 2026-10-09, replacing "hide").
4. **Leaving or declining** a shared event deletes my reminders on it.
5. No end time: "from X before" runs until the start. All-day events offer only persistent all day
   and fixed clock times.
6. **Pause is per person**: each person pauses their own reminders.
7. A shared reminder with "Remind me too" off becomes a shared event with **no owner reminder**.
8. Phones on 1.1.0 **can't sync** once the server is updated (`410`, "update the app"); they keep
   firing local reminders. Server deploy and release go out together.

### Data model (server `V7`, Room 5 -> 6)
- `event`: id, owner, title, notes, `weekdays` (weekly) **or** `on_date` (one-off), `start_minute`
  and `end_minute` (both null = all day; end optional), created/updated, deleted.
- `event_member`: (event, member), updated_at, deleted. The owner is not a row; no rows = personal.
  Leaving tombstones the row; a stale member list from the owner's phone can't add back someone who
  left after it. Replaces `reminder_recipient` (dropped after conversion, with `owner_receives`).
- `reminder.event_id` (next to `chore_id`). An event reminder takes its days from the event.
  Per person per event: at most one **timed** reminder (fixed times, plus "X before" times stored in
  `reminder_time` with `relative = true`) and one **persistent** one (`lead_minutes` null = all day,
  otherwise from X before the start until the end).
- `member_settings` sound values gain `SOUND_VIBRATE`.

### Converting existing reminders (same result on the server and on each phone)
- Ids are derived, not random, so the server's SQL and each phone's Room migration produce the same
  rows: the event reuses the reminder's id, the owner's reminder keeps its id, and extra windows and
  invitee copies get `md5("<reminder id>:<suffix>")` as a UUID. Both sides are tested against the
  same expected UUIDs.
- Done/snooze state moves with each reminder to its new id.
- Rehearsed on a copy of the latest backup in the test stack; manual `pg_dump` before production.

### Old phones (1.1.0) after the server deploys
- Sync moves to a new route; the old `/sync` answers `410 Gone` ("update the app"), never `401`
  (which would send phones back to the join screen). Old phones keep firing their local reminders
  until they update.

### Scheduler
- Relative times and lead windows are computed as instants from the event's start, so "30 min
  before 00:15" lands on the previous day. Dated events fire only on their date. Tests: midnight
  crossing, DST gap, dated event at the lookahead edge.

### Notification channels
- New channel ids (`_v2`) for Sound (no vibration), Vibrate, Sound and vibrate, Silent; the old six
  are deleted.

### UI
- Reminders tab: weekly timetable of events with previous/next week; my reminder times on each event.
- Event editor (owner): title, notes, weekly days or a date, time or all day, shared with.
- Event sheet: details, "Your reminders" (quick times and "X before", persistent all day / from X
  before), Leave (shared with me), Edit/Delete (owner). The Shared screen and invites go away.
- Settings: Sound / Vibrate / Sound and vibrate / Silent.

## Questions as first noted

1. **Repeating vs one-off**: today everything repeats weekly. Events like a dentist appointment
   happen once, on a date. Support dates, weekly repeats, or both?
2. **Do standalone reminders stay?** E.g. "take meds 08:00" with no event behind it. Either keep
   them, or make every reminder belong to an event (an event "Meds" with a reminder).
3. **Who can see and edit an event**: only the creator edits (like reminders today) or anyone
   (like chores)?
4. **"Reminder relative to the event"**, e.g. "15 minutes before", which moves if the event's time
   changes, as well as fixed times?
5. **What happens to today's reminders and shared reminders**: migrate each into an event with a
   reminder attached, or leave them as they are?
6. **Done/attendance**: does an event need a "done" or "going" state, or is it only informational?

## Rough plan once decided

1. Server: events table and CRUD, scoped to the household and visibility; `reminder.event_id`;
   event reminder times published for the timetable (like chore `slots`); sync.
2. App: Room tables and migration (tested), an event editor, and an event sheet with "Your times"
   (reusing the chore-times code); the Reminders tab reworked into the events timetable.
3. Migration of existing reminders and shared reminders, if decided, with backups first.
4. Released through `dev` → `main` → `publish-release.sh`, tried first in the dev app on the test
   server.
