# Tech Stack & Dependencies

## Core
| Layer | Technology |
|---|---|
| Language | Kotlin 1.9.25 |
| Framework | Spring Boot 3.5.6 |
| JVM | Java 21+ |
| Build | Gradle 8.x (Kotlin DSL — `build.gradle.kts`) |

## Persistence
| Technology | Role |
|---|---|
| Spring Data JPA + Hibernate | ORM layer |
| PostgreSQL | Production database |
| H2 (in-memory) | Dev / CI default |
| Flyway 11.8.0 | Schema migrations (`db/migration/V{N}__*.sql`) |

## Authentication & Security
| Technology | Role |
|---|---|
| JJWT 0.12.3 | JWT access + refresh tokens |
| RS256 (RSA) / HS256 (HMAC) | JWT signing (configurable) |
| Firebase Admin SDK 9.2.0 | Google Sign-In token verify + FCM push |
| Spring Security | Filter chain, OAuth2 client support |
| Bucket4j 8.10.1 | Rate limiting (token bucket) |

## External Services
| Service | Integration |
|---|---|
| OpenRouter AI | Vocab extraction, daily insights (REST via WebFlux) |
| Firebase (FCM) | Android/Web push notifications |
| Apple APNs | iOS push notifications |
| RevenueCat | Subscription management (webhook + REST API) |
| GitHub API | Vocab collection repository |

## HTTP & Reactive
| Technology | Role |
|---|---|
| Spring WebFlux (`WebClient`) | Non-blocking HTTP client for external APIs |
| Kotlin Coroutines | Async support |
| Jackson + Kotlin module | JSON serialization |

## Observability
| Technology | Role |
|---|---|
| Spring Boot Actuator | `/actuator/health`, metrics |
| Kotlin Logging JVM 5.1.0 | Structured logging |
| Metabase | Analytics dashboards (reads from `app_events` table) |

## Testing
| Technology | Role |
|---|---|
| JUnit 5 | Test runner |
| MockK 1.13.8 | Kotlin mocking library |
| Spring Boot Test | Integration test support |

## Database Migrations (Flyway)
| Version | Description |
|---|---|
| V1 | Core schema: users, push_tokens, user_settings, words, daily_insights, daily_activities, subscriptions |
| V2–V6 | Refresh token table evolution |
| V7 | Remove deprecated profile/streak columns |
| V8 | Simplify user_settings |
| V9 | Re-add longest_streak |
| V10 | Leaderboard indexes |
| V11 | Re-add profile_image_url |
| V12 | study_sessions, review_events, audit_log |
| V13 | Standalone audit_log table |
| V14 | app_events table (Metabase analytics) |

**Rule:** Always add new `V{N}__description.sql` migration. Never edit existing migration files. `ddl-auto` is `validate`.