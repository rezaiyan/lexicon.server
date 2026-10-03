# Vokab Server

A **Spring Boot + Kotlin** backend for a vocabulary learning mobile app. It provides spaced-repetition word management, Google/Apple authentication, streaks, leaderboards, AI vocabulary extraction, push notifications, and subscription management.

## Features

- **SM-2 Spaced Repetition** — per-word ease factor, interval, and repetition tracking
- **Google & Apple Sign-In** — OAuth via Firebase ID tokens
- **JWT Authentication** — RS256 access tokens + rotating refresh tokens
- **Streaks & Leaderboard** — daily activity tracking and ranked leaderboard
- **AI Vocabulary Extraction** — extract words from images via OpenRouter
- **AI Daily Insights** — personalized motivational messages via push notification
- **Push Notifications** — FCM (Android), APNs (iOS), Web Push
- **Subscription Management** — RevenueCat webhook integration
- **Analytics** — session sync, heatmap, weekly reports, language-pair stats
- **Rate Limiting** — Bucket4j token buckets on auth and AI endpoints
- **Flyway Migrations** — versioned schema management

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Kotlin 1.9.25 |
| Framework | Spring Boot 3.5.6 |
| Security | Spring Security · JJWT 0.12.3 |
| Persistence | Spring Data JPA · Flyway |
| Database | PostgreSQL (prod) · H2 (dev) |
| AI | OpenRouter API |
| Push | Firebase Admin SDK 9.2.0 |
| Subscriptions | RevenueCat Webhooks |
| HTTP Client | Spring WebFlux (WebClient) |

## Getting Started

### Prerequisites

- Java 21+
- Gradle 8.x (wrapper included — no install needed)
- PostgreSQL (production) or H2 in-memory (development — default)

### Quick Start (H2, no database setup)

```bash
# 1. Copy the environment template
cp env.example .env

# 2. Fill in the two required keys
#    GOOGLE_CLIENT_ID  → https://console.cloud.google.com/apis/credentials
#    OPENROUTER_API_KEY → https://openrouter.ai/keys

# 3. Start the server
./gradlew bootRun
```

Server starts at `http://localhost:8080`. Verify with:

```bash
curl http://localhost:8080/api/v1/health
```

### Docker

```bash
docker-compose up -d
```

### Production (PostgreSQL)

```bash
# 1. Create the database
./scripts/setup-db.sh

# 2. Start in production mode
./scripts/start-prod.sh
```

## Configuration

All configuration is injected via environment variables. Copy `env.example` to `.env` and fill in the values.

### Required

| Variable | Description |
|---|---|
| `GOOGLE_CLIENT_ID` | Google OAuth client ID |
| `OPENROUTER_API_KEY` | OpenRouter AI API key |

### Required in Production

| Variable | Description |
|---|---|
| `DATABASE_URL` | PostgreSQL JDBC URL |
| `DATABASE_USERNAME` | Database username |
| `DATABASE_PASSWORD` | Database password |
| `JWT_PRIVATE_KEY_PATH` | Path to RSA private key PEM (RS256) |
| `JWT_PUBLIC_KEY_PATH` | Path to RSA public key PEM (RS256) |

### Optional / Feature Flags

| Variable | Default | Description |
|---|---|---|
| `FIREBASE_PROJECT_ID` | — | Required for Google Sign-In token verification |
| `FIREBASE_SERVICE_ACCOUNT_PATH` | — | Path to service account JSON (enables push notifications) |
| `REVENUECAT_WEBHOOK_SECRET` | — | Webhook signature secret |
| `REVENUECAT_API_KEY` | — | RevenueCat REST API key |
| `GITHUB_TOKEN` | — | GitHub token for vocabulary collections (raises rate limit to 5000/hr) |
| `PREMIUM_FEATURES_ENABLED` | `true` | Enable premium feature gate |
| `AI_IMAGE_EXTRACTION_ENABLED` | `true` | Enable image OCR extraction |
| `AI_DAILY_INSIGHT_ENABLED` | `true` | Enable AI daily push insights |
| `PUSH_NOTIFICATIONS_ENABLED` | `true` | Enable push notifications |
| `PORT` | `8080` | HTTP server port |

## API Reference

Base path: `/api/v1`. All secured endpoints require `Authorization: Bearer <access_token>`.

### Auth

| Method | Path | Auth | Description |
|---|---|---|---|
| POST | `/auth/google` | No | Sign in with Google (Firebase ID token) |
| POST | `/auth/apple` | No | Sign in with Apple (ID token) |
| POST | `/auth/refresh` | No | Rotate access + refresh tokens |
| POST | `/auth/logout` | Yes | Revoke current refresh token |
| DELETE | `/auth/delete-account` | Yes | Delete account and audit |
| GET | `/auth/jwks` | No | Public RSA key in JWKS format |

### Users

| Method | Path | Description |
|---|---|---|
| GET | `/users/me` | Current user profile |
| PATCH | `/users/me` | Update name or display alias |
| POST | `/users/me/avatar` | Upload profile picture (multipart) |
| DELETE | `/users/me/avatar` | Remove profile picture |
| GET | `/users/me/profile-stats` | Activity stats and personal records |
| GET | `/users/feature-flags` | Feature flag state for the client |

### Words

| Method | Path | Description |
|---|---|---|
| GET | `/words` | List all words for the user |
| POST | `/words` | Upsert words (batch) |
| PATCH | `/words/{id}` | Update a single word |
| DELETE | `/words/{id}` | Delete a single word |
| POST | `/words/batch-delete` | Delete multiple words by IDs |
| POST | `/words/batch-update` | Update language pair on multiple words |

### Analytics

| Method | Path | Description |
|---|---|---|
| POST | `/analytics/sync` | Sync study sessions and review events |
| GET | `/analytics/insights` | Difficult words and study patterns |
| GET | `/analytics/daily-stats` | Per-day stats (`from`/`to` query params) |
| GET | `/analytics/weekly-report` | Weekly summary (204 if no recent activity) |
| GET | `/analytics/heatmap` | Activity heatmap data |
| GET | `/analytics/leaderboard` | Leaderboard with the caller's rank |
| GET | `/analytics/mastered-words` | Words that have reached mastery level |
| GET | `/analytics/language-pairs` | Stats grouped by language pair |

### AI

| Method | Path | Auth | Description |
|---|---|---|---|
| POST | `/ai/extract-vocabulary` | Yes | Extract vocabulary from an image (base64) |
| POST | `/ai/generate-insight` | Yes | Generate a personalized daily insight |
| GET | `/ai/health` | No | AI service status |

### Notifications

| Method | Path | Description |
|---|---|---|
| POST | `/notifications/register-token` | Register a push token |
| DELETE | `/notifications/token/{token}` | Deactivate a specific push token |
| DELETE | `/notifications/tokens` | Deactivate all push tokens for the user |

### Other

| Method | Path | Auth | Description |
|---|---|---|---|
| GET | `/health` | No | Server health check |
| POST | `/events` | Yes | Track a client analytics event |
| POST | `/webhooks/revenuecat` | Signature | RevenueCat subscription webhook |

## Project Structure

```
src/main/kotlin/com/alirezaiyan/vokab/server/
├── config/          # Spring config beans (JWT, Firebase, CORS, rate limiting)
├── domain/
│   ├── entity/      # JPA entities (13 total)
│   └── repository/  # Spring Data JPA repositories
├── presentation/
│   ├── controller/  # REST controllers
│   └── dto/         # Request/response DTOs
├── service/         # Business logic
├── security/        # JWT filter, SecurityConfig, token providers
├── scheduler/       # Scheduled jobs (streak reminders, insight generation)
└── exception/       # Global exception handling

src/main/resources/
├── application.yml
└── db/migration/    # Flyway V1–V14
```

## Development

### Run tests

Integration tests run on PostgreSQL 17 (Testcontainers) with the real Flyway migrations, so Docker must be running.

```bash
./gradlew test
```

### Build

```bash
./gradlew build
```

### Coverage report

```bash
./gradlew test jacocoTestReport
open build/reports/jacoco/test/index.html
```

### Smoke-test endpoints

```bash
./scripts/test-endpoints.sh
# With auth:
ACCESS_TOKEN=your_jwt ./scripts/test-endpoints.sh
```

## Deployment

The application ships as a Docker image and can be deployed anywhere containers run:

- **AWS ECS / Fargate**
- **Google Cloud Run**
- **DigitalOcean App Platform**
- **Railway**
- **Any VPS with Docker**

Key production checklist:
- Use PostgreSQL (not H2)
- Persist JWT RSA keys (`./keys/` volume) — losing them invalidates all user sessions
- H2 is not in the production jar; the console exists only under the `h2` profile
- Startup fails without `DATABASE_URL` (no fallback database)
- Put all secrets in environment variables, never in code

## Contributing

1. Fork the repository
2. Create a feature branch (`git checkout -b feat/my-feature`)
3. Run the test suite before opening a PR (`./gradlew test`)
4. Submit a pull request

## License

MIT License — see [LICENSE](LICENSE).
