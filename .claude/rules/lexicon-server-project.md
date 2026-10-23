# Vokab Server — Project Overview

Spring Boot (Kotlin) backend for the Lexicon vocabulary learning app. Provides spaced-repetition word management, Google/Apple auth, streaks, leaderboards, AI vocabulary extraction, push notifications, and subscription management.

## Tech Stack

- **Kotlin** 1.9.25 / **Java** 21 (toolchain)
- **Spring Boot** 3.5.6 — web, data-jpa, security, oauth2-client, validation, actuator, mail, webflux
- **PostgreSQL** (prod) + **H2** (dev/test), **Flyway** 11.8.0 for migrations
- **JJWT** 0.12.3 (RS256/HS256), **Firebase Admin SDK** 9.2.0
- **Bucket4j** 8.10.1 (rate limiting), **BouncyCastle** 1.78.1 (Argon2)
- **kotlin-logging-jvm** 5.1.0, **kotlinx-coroutines** (core + reactor)
- **MockK** 1.13.8 + JUnit 5, **JaCoCo** for coverage

## Project Layout

```
src/main/kotlin/com/alirezaiyan/vokab/server/
├── Application.kt
├── config/          # Spring config beans (JWT, Firebase, CORS, rate limiting, notifications)
├── domain/
│   ├── entity/      # JPA entities (21)
│   └── repository/  # Spring Data repos + Projections.kt
├── presentation/
│   ├── controller/  # REST controllers (21)
│   └── dto/         # Request/response DTOs
├── service/         # Business logic (34) — includes push/, email/ subpackages
├── security/        # JWT filter, SecurityConfig, token providers
├── scheduler/       # Scheduled jobs (streak reminders, insight generation)
├── task/            # Async jobs
├── logging/         # Request/response logging, sensitive data masking
└── exception/       # GlobalExceptionHandler

src/main/resources/
├── application.yml
└── db/migration/    # Flyway migrations (V1, V2, ...)
```

## Key Features

- **Spaced repetition** (SM-2 algorithm on Word entity)
- **Smart notifications** — `SmartNotificationDispatcher`, engagement tracking, timing selection
- **Analytics events** — `EventService.track()` fire-and-forget into `app_events`
- **Tag system** — V19 migration
- **Word Rush** game mode — V27 migration
- **Email system** — templates, scheduling (V31)
- **Subscriptions** — RevenueCat + Apple webhook validation

## Detailed References

See `.claude/` for deeper docs:
- [Architecture & Domain Model](../architecture.md)
- [API Endpoints](../api.md)
- [Tech Stack & Dependencies](../tech-stack.md)
- [Configuration & Environment](../configuration.md)

## Development Commands

| Task | Command |
|------|---------|
| Run locally (H2) | `./gradlew bootRun` |
| Run tests | `./gradlew test` |
| Lint (detekt) | `./gradlew detekt` |
| Build | `./gradlew build` |
| Coverage | `./gradlew jacocoTestReport` |

## Sub-agents

| Agent | Trigger |
|---|---|
| `kotlin-reviewer` | Review Kotlin/Spring code for correctness, security, and conventions |
| `migration-writer` | Write a new Flyway SQL migration |
| `test-writer` | Write JUnit 5 + MockK tests for a service or controller |
| `api-designer` | Design a new REST endpoint following project conventions |
| `e2e` | Feature or bug fix spanning backend + KMP client (`~/projects/Lexicon`) |
