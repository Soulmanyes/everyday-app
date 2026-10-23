# Everyday App

A private Android app for households: a shared timetable of events and chores, with your own reminders on them. Data syncs through a small Ktor + PostgreSQL backend on an Oracle Cloud VM.

- **Events**: one-off or weekly, and up to 31 days long. Share them with some members or everyone, either to take part or only for scheduling. Personal events show to others as Busy, or not at all.
- **Chores**: a shared chore timetable, plus personal chores only you see.
- **Reminders**: timed or persistent reminders on events and chores, each person setting their own.
- **Week and month views**, with a filter for whose events and chores to show.
- **Several households** on one phone. Admins invite others with one-time links or a join code, and approve new phones with an admin PIN.
- **Import from calendar**: a one-time import of events from the phone's calendar, such as Google Calendar.
- **Instant updates**: a push tells phones to sync as soon as something changes.
- **Calls**: members the admin allows can ring others' phones with a short message, like "Come downstairs". The phone rings full screen for 30 seconds, and the answer ("On my way" or "Can't now") goes back to the caller.

The release app is downloaded from https://szekbal.duckdns.org/download/ and prompts when an update is out.

- `android/`: the app (Kotlin, Jetpack Compose)
- `server/`: the API (Kotlin, Ktor, PostgreSQL)
- `deploy/`: the server's docker-compose stack and Caddy config, the download page (`download/`), the apps page (`apps/`), and the scripts that deploy the server, publish a release and back up the database

See [CLAUDE.md](CLAUDE.md) for architecture and build instructions, and [ROADMAP.md](ROADMAP.md) for the plan.

## License

Everyday's own code is released under [CC0 1.0](LICENSE): public domain, no rights reserved. The libraries it uses keep their own licenses. The app lists them, with their license texts, in Settings → Open source licenses. The Gradle wrapper files (`gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar` in `android/` and `server/`) are Gradle's, under Apache 2.0.
