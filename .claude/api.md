# API Endpoints

Base path: `/api/v1`

All secured endpoints require `Authorization: Bearer <access_token>`.

---

## Auth — `/auth`
| Method | Path | Auth | Description |
|---|---|---|---|
| POST | `/google` | No | Google OAuth (Firebase ID token) |
| POST | `/apple` | No | Apple Sign-In (ID token) |
| POST | `/refresh` | No | Rotate access + refresh tokens |
| POST | `/ci-token` | No | CI/dev environment auth |
| POST | `/logout` | Yes | Revoke refresh token |
| POST | `/delete-account` | Yes | Delete account + audit |
| GET | `/jwks` | No | Public RSA key (JWKS format) |

---

## Users — `/users`
| Method | Path | Description |
|---|---|---|
| GET | `/me` | Current user profile |
| PATCH | `/me` | Update name / displayAlias |
| POST | `/me/avatar` | Upload profile picture (multipart) |
| DELETE | `/me/avatar` | Remove profile picture |
| GET | `/me/profile-stats` | Activity stats, personal records |
| GET | `/feature-flags` | Feature toggles (public) |

---

## Words — `/words`
| Method | Path | Description |
|---|---|---|
| GET | `/` | List all words for the user |
| POST | `/` | Upsert words (batch) |
| PATCH | `/{id}` | Update a single word |
| DELETE | `/{id}` | Delete a single word |
| POST | `/batch-delete` | Delete multiple words by IDs |
| POST | `/batch-update` | Update language pair on multiple words |

---

## Analytics — `/analytics`
| Method | Path | Description |
|---|---|---|
| POST | `/sync` | Sync study sessions + review events from client |
| GET | `/insights` | Difficult words, study patterns |
| GET | `/daily-stats` | Per-day stats (accepts `from`/`to` date params) |
| GET | `/difficult-words` | Words with low accuracy |
| GET | `/weekly-report` | Weekly summary — returns 204 if no activity in past 7 days |
| GET | `/heatmap` | Activity heatmap data |
| GET | `/leaderboard` | Leaderboard with calling user's rank |
| GET | `/mastered-words` | Words that have reached mastery level |
| GET | `/language-pairs` | Stats grouped by language pair |

---

## Streak — `/streak`
| Method | Path | Description |
|---|---|---|
| GET | `/` | Current streak + longest streak |
| POST | `/record` | Record study activity for today |

---

## Leaderboard — `/leaderboard`
| Method | Path | Description |
|---|---|---|
| GET | `/` | Global leaderboard with caller's rank |
| GET | `/{userId}` | Stats for a specific user |

---

## Events — `/events`
| Method | Path | Description |
|---|---|---|
| POST | `/` | Track an analytics event (AppEvent table) |

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

## Settings — `/settings`
| Method | Path | Description |
|---|---|---|
| GET | `/` | Get user settings |
| PATCH | `/` | Update settings (language, theme, notifications) |

---

## Subscriptions — `/subscriptions`
| Method | Path | Description |
|---|---|---|
| POST | `/sync` | Reconcile the caller's premium with RevenueCat now; returns `FeatureAccessResponse`. Rate limit 2/min |

Premium state for the app comes from `GET /users/feature-access` (`userAccess`: `hasPremiumAccess`, `source` STORE/GRANT/NONE, `expiresAt`, `willRenew`, `isTrial`).

Admin (`X-Admin-Key`): `POST /admin/subscriptions/reconcile?scope=linked|all` or `?userIds=1,2`.

---

## Push Notifications — `/push-notification`
| Method | Path | Description |
|---|---|---|
| POST | `/register` | Register device push token |
| DELETE | `/unregister` | Deregister device push token |
| GET | `/list` | List registered tokens for user |

---

## Webhooks — `/webhooks`
| Method | Path | Auth | Description |
|---|---|---|---|
| POST | `/revenuecat` | Static `Authorization` header = `REVENUECAT_WEBHOOK_SECRET` | RevenueCat subscription events (deduped by event id) |
| POST | `/apple` | Signature | Apple server-to-server notifications |

---

## Onboarding — `/onboarding`
| Method | Path | Description |
|---|---|---|
| POST | `/suggest-vocabulary` | AI-generated vocabulary list for a topic |
| POST | `/import-vocabulary` | Batch import suggested words |

---

## AI — `/ai`
| Method | Path | Description |
|---|---|---|
| POST | `/extract-from-image` | OCR + vocabulary extraction from image |
| POST | `/generate-daily-insight` | Generate an AI daily insight |

---

## Health — `/`
| Method | Path | Auth | Description |
|---|---|---|---|
| GET | `/health` | No | Health check |
| GET | `/version` | No | App version info |
