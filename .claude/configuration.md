# Configuration & Environment Variables

## application.yml Summary

All secrets + env-specific values injected via env vars. Without `DATABASE_URL` the app refuses to start; use the `h2` profile for local dev.

## Required in Production

| Env Var | Purpose |
|---|---|
| `DATABASE_URL` | PostgreSQL JDBC URL (e.g., `jdbc:postgresql://host:5432/vokab`) |
| `DATABASE_USERNAME` | DB username |
| `DATABASE_PASSWORD` | DB password |
| `DATABASE_DRIVER` | `org.postgresql.Driver` in prod |
| `JWT_SECRET` | HMAC secret (HS256 mode) OR use RSA keys below |
| `JWT_PRIVATE_KEY` | Base64-encoded RSA private key (RS256 mode) |
| `JWT_PUBLIC_KEY` | Base64-encoded RSA public key (RS256 mode) |
| `OPENROUTER_API_KEY` | API key for OpenRouter AI |
| `REVENUECAT_WEBHOOK_SECRET` | Webhook signature secret |
| `REVENUECAT_API_KEY` | RevenueCat REST API key |
| `FIREBASE_SERVICE_ACCOUNT_PATH` | Path to Firebase service account JSON |

## Optional / Feature Flags

| Env Var | Default | Description |
|---|---|---|
| `PORT` | `8080` | HTTP server port |
| `JWT_EXPIRATION_MS` | `86400000` | Access token TTL (24h) |
| `JWT_REFRESH_EXPIRATION_MS` | `7776000000` | Refresh token TTL (90d) |
| `JWT_ISSUER` | `vokab-server` | JWT `iss` claim |
| `JWT_AUDIENCE` | `vokab-client` | JWT `aud` claim |
| `PREMIUM_FEATURES_ENABLED` | `true` | Enable premium gate |
| `AI_IMAGE_EXTRACTION_ENABLED` | `true` | Enable image OCR feature |
| `AI_DAILY_INSIGHT_ENABLED` | `true` | Enable daily AI insights |
| `PUSH_NOTIFICATIONS_ENABLED` | `true` | Enable push notifications |
| `SUBSCRIPTIONS_ENABLED` | `true` | Enable subscription management |
| `FREE_AI_EXTRACTION_LIMIT` | `10` | Free tier AI extraction cap |
| `VOCABULARY_SUGGESTION_COUNT` | `50` | AI vocabulary suggestions count |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:3000,...` | CORS whitelist |
| `FLYWAY_ENABLED` | `true` | Run Flyway on startup |
| `REQUEST_LOGGING_ENABLED` | `true` | Log HTTP requests |
| `CI_TEST_AUTH_ENABLED` | `false` | Enable CI-only auth bypass |
| `CI_TEST_SECRET` | — | CI auth secret |
| `CI_TEST_EMAIL` | `ci-maestro@test.vokab.dev` | CI test user email |
| `TEST_EMAILS` | — | Comma-separated emails that bypass `active` check |
| `GITHUB_TOKEN` | — | GitHub API token for vocabulary collections |
| `OPENROUTER_BASE_URL` | `https://openrouter.ai/api/v1` | OpenRouter base URL |

## AppProperties (Kotlin)

Structured config via `@ConfigurationProperties`:
- `app.jwt.*` → `JwtProperties`
- `app.openrouter.*` → OpenRouter settings
- `app.features.*` → feature flags (checked by `FeatureAccessService`)
- `app.cors.allowed-origins` → CORS
- `app.ci-auth.*` → CI test auth
- `app.security.testEmails` → bypass active-user check

## Local Development

The `h2` profile uses an in-memory DB (`scripts/start-dev.sh` sets it):
```bash
SPRING_PROFILES_ACTIVE=h2 ./gradlew bootRun
```
H2 console at `http://localhost:8080/h2-console` (JDBC URL: `jdbc:h2:mem:vokabdb`).

Tests (`./gradlew test`) run on PostgreSQL 17 via Testcontainers with the real Flyway migrations, so Docker must be running.

Scripts:
- `scripts/start-dev.sh` — local dev
- `scripts/start-prod.sh` — prod mode with PostgreSQL
- `scripts/test-endpoints.sh` — smoke test API
- `scripts/setup-db.sh` — initialize DB