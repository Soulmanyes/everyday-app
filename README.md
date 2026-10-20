# Everyday App

A private Android app for households: custom reminders on chosen days and times, and a shared chore timetable. Data syncs through a small Ktor + PostgreSQL backend on an Oracle Cloud VM.

- `android/`: the app (Kotlin, Jetpack Compose)
- `server/`: the API (Kotlin, Ktor, PostgreSQL)
- `deploy/`: docker-compose stack for the server

See [CLAUDE.md](CLAUDE.md) for architecture and build instructions, and [ROADMAP.md](ROADMAP.md) for the plan.

## License

Everyday's own code is released under [CC0 1.0](LICENSE): public domain, no rights reserved. The libraries it uses keep their own licenses. The app lists them, with their license texts, in Settings → Open source licenses. The Gradle wrapper files (`gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar` in `android/` and `server/`) are Gradle's, under Apache 2.0.
