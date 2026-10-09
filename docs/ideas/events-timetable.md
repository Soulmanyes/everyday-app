# Idea: a timetable of events, with optional reminders

Status: **not started, not yet discussed in detail** (noted 2026-10-09).
Roadmap entry: "Later / ideas" in `ROADMAP.md`.

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

## Questions to decide

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
