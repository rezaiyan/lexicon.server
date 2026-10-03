# Email & Newsletter Service

## Overview

Provider-based email system for transactional emails and newsletters. Disabled by default. Pluggable providers (Resend, SES, SMTP) via `EmailProvider` interface.

## Architecture

```
EmailController          (REST API for subscription management)
       │
EmailService             (orchestrator: template rendering, dedup, logging)
       │
  ┌────┴────┐
  │         │
EmailSubscriptionService  EmailProvider (interface)
  │                        ├── ResendEmailProvider
  │                        ├── LogOnlyEmailProvider (default)
  │                        └── (add your own)
  │
  ├── EmailSubscriptionRepository
  ├── EmailLogRepository
  └── EmailTemplateRepository
```

## Database Tables

| Table | Purpose |
|---|---|
| `email_subscriptions` | Per-user opt-in/out per category (newsletter, product_updates, weekly_digest) |
| `email_log` | Audit trail: every email sent, status, provider ID, errors |
| `email_templates` | DB-managed HTML templates with `{{variable}}` placeholders |

Migration: `V10__create_email_tables.sql`

## Configuration

Environment variables (all optional, email disabled by default):

```bash
APP_EMAIL_ENABLED=true                          # Master switch
APP_EMAIL_PROVIDER=resend                       # 'resend' or 'log' (default)
APP_EMAIL_API_KEY=re_xxxxxxxxxxxx               # Resend API key
APP_EMAIL_FROM_ADDRESS=Lexicon <noreply@mail.lexicon.app>
APP_EMAIL_REPLY_TO=support@lexicon.app
```

Maps to `EmailConfig` (`app.email.*` prefix in `application.yml`).

## API Endpoints

All require auth (`Authorization: Bearer <token>`).

### GET /api/v1/email/preferences

Returns user's email subscription preferences.

```json
{
  "success": true,
  "data": [
    { "category": "newsletter", "subscribed": true },
    { "category": "product_updates", "subscribed": true },
    { "category": "weekly_digest", "subscribed": false }
  ]
}
```

### POST /api/v1/email/subscribe

Subscribe to category.

```json
// Request
{ "category": "weekly_digest" }

// Response
{ "success": true, "data": { "category": "weekly_digest", "subscribed": true } }
```

### POST /api/v1/email/unsubscribe

Unsubscribe from category.

```json
// Request
{ "category": "newsletter" }

// Response
{ "success": true, "data": { "category": "newsletter", "subscribed": false } }
```

## Sending Emails

### Templated (recommended)

```kotlin
emailService.sendTemplated(
    userId = user.id!!,
    recipientEmail = user.email,
    templateId = "welcome",
    variables = mapOf("name" to user.name, "streak" to "5"),
    dedupHours = 24  // skip if same template sent within 24h
)
```

Steps:
1. Check `emailConfig.enabled`
2. Look up template by ID (must be `active = true`)
3. Check user subscription preference for template's category
4. Dedup against `email_log`
5. Render `{{variables}}` in subject, HTML body, text body
6. Send via configured provider
7. Log result to `email_log`

### Raw (no template)

```kotlin
emailService.sendRaw(
    userId = user.id,
    recipientEmail = user.email,
    category = "transactional",
    templateId = "custom-alert",
    subject = "Your streak is about to end!",
    bodyHtml = "<h1>Hey ${user.name}!</h1><p>...</p>"
)
```

## Email Templates

Stored in `email_templates` table. Insert via migration or admin tooling.

| Field | Description |
|---|---|
| `id` | Unique key, e.g. `welcome`, `weekly_digest`, `streak_lost` |
| `subject` | Subject with `{{variable}}` support |
| `body_html` | HTML body with `{{variable}}` support |
| `body_text` | Plain text fallback (optional) |
| `category` | Maps to subscription categories |
| `active` | Toggle without deleting |

Example:

```sql
INSERT INTO email_templates (id, name, subject, body_html, category) VALUES
('welcome', 'Welcome Email', 'Welcome to Lexicon, {{name}}!',
 '<h1>Welcome, {{name}}!</h1><p>Start building your vocabulary today.</p>',
 'product_updates');
```

## Subscription Categories

Default categories (initialized on signup via `EmailSubscriptionService.initDefaults`):

| Category | Description |
|---|---|
| `newsletter` | Announcements, tips, content |
| `product_updates` | New features, releases, changelogs |
| `weekly_digest` | Weekly progress summary |

Add categories by updating `EmailSubscriptionService.DEFAULT_CATEGORIES`.

## Adding a New Provider

1. Implement `EmailProvider`:

```kotlin
@Component
@ConditionalOnProperty(name = ["app.email.provider"], havingValue = "ses")
class SesEmailProvider(private val emailConfig: EmailConfig) : EmailProvider {
    override val name = "ses"
    override fun send(request: EmailSendRequest): EmailSendResult { ... }
}
```

2. Set `APP_EMAIL_PROVIDER=ses` in environment.

Spring auto-selects matching provider via `@ConditionalOnProperty`.

## Email Log & Monitoring

Every attempt logged in `email_log`:

| Status | Meaning |
|---|---|
| `QUEUED` | Created, about to send |
| `SENT` | Provider accepted |
| `FAILED` | Provider error (see `error_message`) |
| `BOUNCED` | Delivery failed (update via webhook) |

Query recent failures:

```sql
SELECT * FROM email_log WHERE status = 'FAILED' ORDER BY created_at DESC LIMIT 20;
```

## Integration Points

- **User registration**: Call `emailSubscriptionService.initDefaults(userId)` after user created
- **Welcome email**: Call `emailService.sendTemplated(userId, email, "welcome", ...)` after registration
- **Weekly digest**: Add scheduled job in `ScheduledTasks.kt`, query active users, send digest
- **Streak reminders**: Use alongside push notifications as fallback channel