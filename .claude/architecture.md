# Architecture & Domain Model

## Domain Entities (13 total)

### User
Core account record. Auth via Google (`googleId`) or Apple (`appleId`).
- `subscriptionStatus`: `FREE | TRIAL | ACTIVE | EXPIRED | CANCELLED`
- `currentStreak`, `longestStreak`: Maintained by `StreakService`
- `displayAlias`, `profileImageUrl`: Used on leaderboard
- `active`: Soft-delete flag. Inactive users blocked from API.
- `revenueCatUserId`: Links to RevenueCat for subscription events.

### Word
Vocab card with **SM-2 spaced repetition** fields:
- `level` (0–6+), `easeFactor` (default 2.5), `interval` (days), `repetitions`
- `lastReviewDate`, `nextReviewDate`: Unix ms timestamps
- `@Version`: Optimistic locking — handle `OptimisticLockingFailureException`
- `sourceLanguage`, `targetLanguage`: Max 10 chars (e.g., `"en"`, `"fa"`)

### StudySession
One session per review round on client.
- `clientSessionId`: Client-generated UUID. Unique per `(user_id, client_session_id)` — prevents duplicate syncs.
- `durationMs`, `totalCards`, `correctCount`, `incorrectCount`
- `completedNormally`: `false` if session abandoned mid-way.

### ReviewEvent
Individual word review within session.
- `wordId` stored but **not a foreign key** — words deletable without losing history.
- `wordText`, `wordTranslation`, `sourceLanguage`, `targetLanguage`: Denormalized snapshot at review time.
- `rating` (1–5), `previousLevel`, `newLevel`, `responseTimeMs`

### Subscription
RevenueCat subscription record.
- `revenueCatSubscriptionId`: Unique identifier from RevenueCat.
- Lifecycle managed by webhook at `POST /api/v1/webhooks/revenuecat`.

### UserSettings
One-to-one with User.
- `languageCode` (ISO, default `"en"`), `themeMode` (`"AUTO"|"LIGHT"|"DARK"`)
- `notificationsEnabled`, `dailyReminderTime`

### PushToken
Many-to-one with User. One user, multiple devices.
- `platform`: `ANDROID | IOS | WEB`
- `token`: Unique per device (FCM/APNs/Web Push token).
- `active`: Set `false` to stop sending without deleting.

### DailyInsight
AI-generated insight pushed once per day per user.
- `date`: `"YYYY-MM-DD"` — unique per `(user_id, date)`.

### DailyActivity
One record per day user does any review. Used to compute streaks.
- Unique constraint on `(user_id, activity_date)`.

### RefreshToken
Hashed refresh tokens for secure rotation.
- `tokenHash`: SHA-256 hash (Argon2 preferred). Never store raw token.
- `revoked`: True after use (rotation) or logout.

### AuditLog
Security event log (login, refresh, logout, deletion).
- No FK on `userId` — survives account deletion.
- `eventType`: Enum (LOGIN, TOKEN_REFRESH, LOGOUT, ACCOUNT_DELETE, etc.)
- `metadata`: JSON string for extra context.

### AppEvent
Client-side analytics for Metabase dashboards.
- No FK on `userId` — analytics survive account deletion.
- `eventName`: Max 100 chars (e.g., `"login"`, `"onboarding_complete"`, `"word_added"`)
- `properties`: JSON string for event-specific params.
- `platform`: `"android" | "ios"`
- `clientTimestamp` vs `serverTimestamp`: Client reports time; server records receipt.

### NotificationCategory
Categorizes push notification types.

---

## Service Responsibilities

| Service | Responsibility |
|---|---|
| `AuthService` | Google/Apple login, token issuance, account deletion |
| `UserService` | Profile CRUD, display alias validation |
| `WordService` | Word CRUD, batch ops, uses `WordUpsertPreparer` for SM-2 defaults |
| `StreakService` | Streak calculation, `DailyActivity` upsert |
| `AnalyticsService` | Session sync, insights, daily/weekly stats, heatmap |
| `SubscriptionService` | RevenueCat webhook processing, subscription lifecycle |
| `LeaderboardService` | Score = mastered words × 10 + currentStreak × 3 + longestStreak × 2 |
| `EventService` | Write `AppEvent` records; wraps tracking in try/catch |
| `DailyInsightService` | Generate/send AI daily insights via OpenRouter |
| `FeatureAccessService` | Feature flag checks for premium/AI/notification access |
| `OpenRouterService` | HTTP calls to OpenRouter AI API |
| `PushNotificationService` | Route and send FCM/APNs/Web Push |
| `PushTokenService` | Token registration, deactivation |
| `AuditLogService` | Write security events to `AuditLog` |
| `UserSettingsService` | Read/write `UserSettings` |
| `RefreshTokenHashService` | Argon2 hashing for refresh tokens |
| `AvatarService` | Profile image upload/storage |
| `AliasGenerator` | Generate anonymous display aliases |

---

## Security Architecture

```
Request → JwtAuthenticationFilter → SecurityConfig → Controller → Service
```

- **JwtAuthenticationFilter**: Validates Bearer token, loads `User` entity, sets `SecurityContext`.
- **SecurityConfig**: Stateless session, public route whitelist.
- **RS256** (RSA) or **HS256** (HMAC) — configured via `JWT_PRIVATE_KEY` / `JWT_SECRET`.
- **JWKS endpoint** at `/api/v1/auth/jwks` exposes public key.
- **Rate limiting**: Bucket4j token bucket on sensitive endpoints.