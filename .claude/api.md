# API Endpoints

Base path: `/api/v1` (admin endpoints under `/admin`, not versioned).

All responses wrapped in `ApiResponse<T>`: `{ "success": Boolean, "data": T?, "message": String? }`.

Secured endpoints require `Authorization: Bearer <access_token>` (RS256 JWT), resolved to `@AuthenticationPrincipal user: User`.
Admin endpoints (`/admin/**`) are not user-authenticated — protect at the infra/reverse-proxy layer.

_Last regenerated from source: 2026-08-01._

---

## Auth — `/auth` (`AuthController`, `JwksController`)
| Method | Path | Auth | Description |
|---|---|---|---|
| POST | `/google` | No | Google OAuth (Firebase ID token) |
| POST | `/apple` | No | Apple Sign-In (ID token) |
| POST | `/ci-token` | No (gated by `X-CI-Secret`) | CI/dev auth, only when `ciAuth.enabled` |
| POST | `/refresh` | No | Rotate access + refresh tokens |
| POST | `/logout` | Yes | Revoke one refresh token |
| POST | `/logout-all` | Yes | Revoke all sessions for the user |
| DELETE | `/delete-account` | Yes | Delete account |
| GET | `/jwks` | No | Public RSA key set (JWKS, for RS256 verification) |

---

## Users — `/users` (`UserController`)
| Method | Path | Auth | Description |
|---|---|---|---|
| GET | `/me` | Yes | Current user profile |
| PATCH | `/me` | Yes | Update `name` / `displayAlias` |
| POST | `/me/avatar` | Yes | Upload avatar (multipart `file`) |
| DELETE | `/me/avatar` | Yes | Remove avatar |
| DELETE | `/me` | Yes | Delete account (duplicate of `/auth/delete-account`) |
| GET | `/feature-access` | Yes | Feature flags + this user's access |
| GET | `/feature-flags` | No | Global feature flags only |
| GET | `/profile-stats` | Yes | Activity stats, personal records |

---

## Words — `/words` (`WordController`)
| Method | Path | Auth | Description |
|---|---|---|---|
| GET | `/?updatedAfter=` | Yes | List user words, optional incremental sync cursor (epoch millis) |
| POST | `/` | Yes | Upsert words (batch) |
| PATCH | `/{id}` | Yes | Update single word |
| DELETE | `/{id}` | Yes | Delete single word |
| POST | `/batch-delete` | Yes | Delete words by IDs |
| PUT | `/{id}/tags` | Yes | Replace tags on a single word |
| POST | `/batch-assign-tags` | Yes | Assign tags across multiple words |
| POST | `/batch-update` | Yes | Update source/target language pair on words |

---

## Tags — `/tags` (`TagController`)
| Method | Path | Auth | Description |
|---|---|---|---|
| GET | `/` | Yes | List user's tags |
| POST | `/` | Yes | Create tag (`{name}`) → 201 |
| PUT | `/{id}` | Yes | Rename tag |
| DELETE | `/{id}` | Yes | Delete tag → 204 |

---

## Analytics — `/analytics` (`AnalyticsController`)
| Method | Path | Auth | Description |
|---|---|---|---|
| POST | `/sync` | Yes | Sync study sessions + review events |
| GET | `/insights` | Yes | Difficult words, study patterns |
| GET | `/daily-stats?start=&end=` | Yes | Per-day stats |
| GET | `/difficult-words?minReviews=&limit=` | Yes | Low-accuracy words |
| GET | `/most-reviewed?limit=` | Yes | Most-reviewed words |
| GET | `/accuracy-by-level` | Yes | Accuracy grouped by SRS level |
| GET | `/accuracy-by-hour` | Yes | Accuracy grouped by hour of day |
| GET | `/accuracy-by-day-of-week` | Yes | Accuracy grouped by day of week |
| GET | `/sessions?limit=` | Yes | Recent study sessions |
| GET | `/heatmap?start=&end=` | Yes | Activity heatmap (epoch millis range) |
| GET | `/level-transitions` | Yes | How words move between SRS levels |
| GET | `/words-mastered?limit=` | Yes | Words at mastery level |
| GET | `/language-stats` | Yes | Stats by language pair |
| GET | `/monthly-stats` | Yes | Per-month stats |
| GET | `/response-time-trend` | Yes | Review response-time trend over time |
| GET | `/comeback-words` | Yes | Previously-mastered words needing review again |
| GET | `/weekly-report` | Yes | Weekly summary (client renders `WeeklyReportCard`) |

All 17 endpoints have a client caller (`AnalyticsStatsRemoteDataSource` / `AnalyticsWordRemoteDataSource`) as of 2026-08-01 — no orphaned endpoints here.

---

## Word Rush — `/word-rush` (`WordRushController`)
| Method | Path | Auth | Description |
|---|---|---|---|
| POST | `/sync` | Yes | Sync completed Word Rush games |
| GET | `/insights` | Yes | Word Rush performance insights |
| GET | `/history` | Yes | Past game history |

---

## Streak — `/streak` (`StreakController`)
| Method | Path | Auth | Description |
|---|---|---|---|
| GET | `/` | Yes | Current + longest streak |
| POST | `/record` | Yes | Record today's study activity (`{count}`, default 1) |

---

## Leaderboard — `/leaderboard` (`LeaderboardController`)
| Method | Path | Auth | Description |
|---|---|---|---|
| GET | `/?limit=` | Yes | Global leaderboard (1-100) + caller rank |

Note: earlier docs also listed `GET /{userId}` — not present in current `LeaderboardController`; the per-user variant does not exist in code.

---

## Events — `/events` (`EventController`)
| Method | Path | Auth | Description |
|---|---|---|---|
| POST | `/` | Yes | Track analytics event (`AppEvent` table). Never fails the caller — absorbs errors and returns success. |

Request body:
```json
{
  "eventName": "onboarding_complete",
  "properties": {"step": "language_select"},
  "platform": "ios",
  "appVersion": "1.4.2",
  "clientTimestamp": 1710000000000
}
```

---

## Settings — `/settings` (`SettingsController`)
| Method | Path | Auth | Description |
|---|---|---|---|
| GET | `/` | Yes | Get user settings |
| PATCH | `/` | Yes | Update settings (language, theme, notifications, `reviewReminders`) |

---

## Subscriptions — `/subscriptions` (`SubscriptionController`)
| Method | Path | Auth | Description |
|---|---|---|---|
| POST | `/sync` | Yes | Reconcile the caller's premium with RevenueCat now; returns `FeatureAccessResponse`. Rate limit 2/min |

Premium state for the app comes from `GET /users/feature-access` (`userAccess`: `hasPremiumAccess`, `source` STORE/GRANT/NONE, `expiresAt`, `willRenew`, `isTrial`).

Admin (`X-Admin-Key`): `POST /admin/subscriptions/reconcile?scope=linked|all` or `?userIds=1,2`.

---

## Push Notifications — `/notifications` (`PushNotificationController`)
| Method | Path | Auth | Description |
|---|---|---|---|
| POST | `/register-token` | Yes | Register FCM/APNs token for this device |
| DELETE | `/token/{token}` | Yes | Deactivate a single device's token (use on per-device logout) |
| DELETE | `/tokens` | Yes | Deactivate **all** tokens for the user (use on account deletion only) |
| POST | `/send` | Yes | Send a push notification |
| GET | `/tokens` | Yes | Count of user's registered tokens |

Path was previously documented as `/push-notification` with `/register` `/unregister` `/list` — those never existed; corrected above.

---

## Email — `/email` (`EmailController`)
| Method | Path | Auth | Description |
|---|---|---|---|
| GET | `/preferences` | Yes | List email category subscriptions |
| POST | `/subscribe` | Yes | Subscribe to a category (`{category}`) |
| POST | `/unsubscribe` | Yes | Unsubscribe from a category (`{category}`) |

---

## AI — `/ai` (`AiController`)
| Method | Path | Auth | Description |
|---|---|---|---|
| POST | `/extract-vocabulary` | Yes, premium | OCR + vocab extraction from image, rate-limited |
| GET | `/generate-insight` | Yes, premium | Generate/return today's AI daily insight, rate-limited |
| POST | `/translate-text` | Yes | Translate short text (≤200 chars, ≤2 lines), rate-limited |
| POST | `/suggest-vocabulary` | Yes | AI-generated vocab list from target/native language + level, rate-limited |
| GET | `/health` | Yes | AI service health/model info |

---

## Onboarding — `/onboarding` (`OnboardingController`)
| Method | Path | Auth | Description |
|---|---|---|---|
| POST | `/preferences` | No (public, IP rate-limited) | Submit target/native language + level + interests → suggested vocabulary |

Note: earlier docs listed `/suggest-vocabulary` and `/import-vocabulary` here — those don't exist on this controller; the real (and only) endpoint is `/preferences`. The similarly-named authenticated `/ai/suggest-vocabulary` above is a separate endpoint.

---

## Webhooks — `/webhooks` (`WebhookController`, `AppleWebhookController`)
| Method | Path | Auth | Description |
|---|---|---|---|
| POST | `/revenuecat` | Static `Authorization` header = `REVENUECAT_WEBHOOK_SECRET` | RevenueCat subscription events (deduped by event id) |
| POST | `/apple` | JWT payload verification | Apple server-to-server notifications |
| GET | `/apple` | No | Webhook reachability health check |

---

## Health — `/` (`HealthController`)
| Method | Path | Auth | Description |
|---|---|---|---|
| GET | `/health` | No | Health check (JSON or HTML status page via `Accept` header) |
| GET | `/version` | No | App version info |

---

## Admin — `/admin` (not versioned, no user auth — restrict at infra layer)

### App Config — `/admin/config` (`AppConfigAdminController`)
| Method | Path | Description |
|---|---|---|
| GET | `/` | List all app config entries |
| GET | `/{namespace}/{key}` | Get one config entry |
| PUT | `/{namespace}/{key}` | Set config value (`X-Changed-By` header optional) |
| POST | `/{namespace}/{key}/items` | Add item to a list-type config |
| DELETE | `/{namespace}/{key}/items/{item}` | Remove item from a list-type config |
| GET | `/{namespace}/{key}/history` | Change history for a config entry |

### Notifications — `/admin/notifications` (`NotificationAdminController`)
| Method | Path | Description |
|---|---|---|
| GET | `/stats` | Push notification engagement stats |
