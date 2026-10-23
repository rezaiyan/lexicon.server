# Changelog

All notable changes to Vokab Server are documented here.

Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
Versions follow [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [Unreleased]

---

## [1.0.2] - 2026-10-23

### Added
- MCP server for AI apps: OAuth 2.1 sign-in, word search with paging, add/tag/delete words,
  due words and progress tools, and a list of connected apps you can disconnect
- AI credits with a free tier and a monthly premium allowance, flat per-feature prices and
  per-user call limits; the daily insight is free for everyone
- Learner instructions and quick picks for photo extraction and vocabulary suggestions, and
  language-aware word identity with v2 photo extraction
- Insights screen (`GET /analytics/insights-screen`) with a coach rule engine, and storage of
  Listening sessions
- Smarter notifications: AI-backed advisor with engagement segments, timezone-aware timing,
  short "bite" due pushes, Word Rush and Listening nudges, open tracking, and comeback rescue
  pushes planned and checked by AI
- Admin notifications dashboard with secure sign-in and manual sends, in-app support chat
  answered from Telegram, runtime config table and email service (SMTP)
- Operations: Docker image published by CI with automatic deploy, health check and rollback,
  nightly database backups, external uptime probe, readiness-backed `/api/v1/health`, JSON
  logs, Prometheus metrics, and an OpenAPI contract checked in CI

### Changed
- Requests, `@Async` and `@Scheduled` jobs run on virtual threads; the production image runs
  on Java 25
- Releases only ship commits whose CI passed on main, reusing the image CI built and scanned

### Security
- Apple ID tokens: verified audience and nonce, and only the token subject is trusted
- Refresh tokens rotate in families; reuse revokes the family, and revoked tokens are rejected
- RevenueCat and Apple webhooks fail closed and verify signatures and audience
- Bounded AI cost and client input, stricter rate limits and client IP handling, scoped word
  upserts, and account deletion purges every user-owned table

### Fixed
- Spring MVC request errors return 4xx instead of 500
- Duplicate notification sends and several PostgreSQL query incompatibilities
- MCP connection-pool deadlock under concurrent calls

---

## [1.0.1] - 2026-03-29

### Added
- Delta sync for words and faster word repository operations
- Word Rush sync and persisted daily insights

---

## [1.0.0] - 2026-03-28

### Added
- Google and Apple Sign-In authentication with JWT token issuance
- Spaced-repetition word management (CRUD, batch delete, tagging)
- Word sync endpoint for offline-first mobile clients
- Streak tracking with daily activity detection
- Leaderboard across all users
- AI vocabulary extraction via OpenRouter (AiController)
- Word Rush mini-game with session sync, analytics, and daily insights
- Push notifications via Firebase Cloud Messaging (FCM)
- RevenueCat webhook integration for subscription management
- Apple App Store server-to-server notifications
- Account deletion with full cascade cleanup (GDPR)
- Flyway-managed schema migrations (V1–V28)
- JaCoCo code coverage reporting with 80% threshold enforcement
- Rate limiting on auth and AI endpoints via Bucket4j
- Health check endpoint (`/actuator/health`)

[Unreleased]: https://github.com/rezaiyan/lexicon.server/compare/v1.0.2...HEAD
[1.0.2]: https://github.com/rezaiyan/lexicon.server/compare/v1.0.1...v1.0.2
[1.0.1]: https://github.com/rezaiyan/lexicon.server/compare/v1.0.0...v1.0.1
[1.0.0]: https://github.com/rezaiyan/lexicon.server/releases/tag/v1.0.0
