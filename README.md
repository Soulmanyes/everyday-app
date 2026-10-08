# Everyday App

A private Android app for our household: custom reminders on chosen days and times, and a shared chore timetable. Data syncs through a small Ktor + PostgreSQL backend on an Oracle Cloud VM.

- `android/`: the app (Kotlin, Jetpack Compose)
- `server/`: the API (Kotlin, Ktor, PostgreSQL)
- `deploy/`: docker-compose stack for the server

See [CLAUDE.md](CLAUDE.md) for architecture and build instructions, and [ROADMAP.md](ROADMAP.md) for the plan.
