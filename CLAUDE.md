# Vokab Server — Claude Code Context

Spring Boot (Kotlin) backend for Lexicon vocabulary app. Spaced-repetition word management, Google/Apple auth, streaks, leaderboards, AI vocab extraction, smart push notifications, email, tags, Word Rush game, subscription management.

## Rules

Project rules in `.claude/rules/` — modular, path-scoped. Load only when editing matching files.

| Rule                                                                      | Loaded when editing                                                  |
| ------------------------------------------------------------------------- | -------------------------------------------------------------------- |
| [lexicon-server-project.md](.claude/rules/lexicon-server-project.md)      | Every session — tech stack, layout, commands, sub-agents             |
| [lexicon-server-kotlin.md](.claude/rules/lexicon-server-kotlin.md)        | `src/main/kotlin/**/*.kt` — layering, DI, logging, transactions      |
| [lexicon-server-api.md](.claude/rules/lexicon-server-api.md)              | `presentation/**`, `exception/**` — REST conventions, ApiResponse     |
| [lexicon-server-flyway.md](.claude/rules/lexicon-server-flyway.md)        | `db/migration/**`, `domain/entity/**` — migration rules, schema      |
| [lexicon-server-security.md](.claude/rules/lexicon-server-security.md)    | `security/**`, `config/**`, auth/webhook controllers                 |
| [lexicon-server-testing.md](.claude/rules/lexicon-server-testing.md)      | `src/test/**` — MockK, factories, controller tests                   |

## Detailed References

- [Architecture & Domain Model](.claude/architecture.md)
- [API Endpoints](.claude/api.md)
- [Tech Stack & Dependencies](.claude/tech-stack.md)
- [Configuration & Environment](.claude/configuration.md)

## Sub-agents

| Agent              | Trigger                                                        |
| ------------------ | -------------------------------------------------------------- |
| `kotlin-reviewer`  | Review Kotlin/Spring code for correctness and conventions      |
| `migration-writer` | Write new Flyway SQL migration                                 |
| `test-writer`      | Write JUnit 5 + MockK tests for service or controller         |
| `api-designer`     | Design new REST endpoint following project conventions        |
| `e2e`              | Feature or bugfix spanning backend + KMP client (`~/projects/Lexicon`) |