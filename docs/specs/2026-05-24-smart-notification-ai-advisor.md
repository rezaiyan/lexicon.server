# Smart Notification AI Advisor

**Date:** 2026-05-24  
**Status:** Approved — ready for implementation  
**Scope:** Notification engagement intelligence — segment-aware frequency + AI-backed motivation for cold/dormant users

---

## Problem

Current system throttles notifications via a static `consecutiveIgnores` ladder (1→2→3→7→14→30 days). It treats all non-openers the same and has no ability to adapt content tone. Once a user hits 15+ ignores they get 30-day silence with no re-engagement strategy.

**Gaps:**
- No segment model — a user who opened yesterday and one who hasn't opened in 3 weeks get identical treatment
- Content never adapts to emotional state (loss aversion, curiosity, fresh start)
- No AI involvement — non-deterministic creative decisions (what message will land?) handled by static templates

---

## Solution Overview

**Hybrid logic + AI.** Rules handle HOT/WARM/COOLING users (80% of base). For COLD/DORMANT users, Claude Haiku makes a structured decision (send/pause/motivate + interval + content angle) cached 7 days per user. The final notification copy is always a pre-written template — AI only selects the angle, never generates free text.

---

## Engagement Segments

Computed nightly from `notification_log` (rolling 30-day window). Stored on `notification_schedule.engagement_segment`.

| Segment | Criteria | Default Send Frequency |
|---------|----------|------------------------|
| `HOT` | Opened ≥2 of last 3 sent | Daily, all types |
| `WARM` | Opened 1 of last 3 sent | Daily, higher-value types |
| `COOLING` | Opened 0 of last 3, last open <7 days ago | Every 2 days |
| `COLD` | Last open 7–30 days ago (or <10 sent, never opened) | AI decides (3–7 days) |
| `DORMANT` | Last open >30 days ago | AI decides (7–14 days) |

**Transitions:**
- Any `openedAt` recorded → segment immediately resets to `WARM`, AI cache invalidated
- Consecutive non-opens → drift down naturally on next nightly recompute
- New users (no log history) → start at `WARM`

---

## AI Advisor

### When it fires
Only for `COLD` and `DORMANT` users where `ai_decided_at IS NULL OR ai_decided_at < now() - 7 days`.
Runs as part of the nightly `EngagementSegmentScheduler`, after segment computation.

### Model
Claude Haiku (`claude-haiku-4-5-20251001`) — cheapest capable model. Non-blocking async call.

### Input (~200 tokens)
```json
{
  "segment": "COLD",
  "openRate7d": 0.0,
  "openRate30d": 0.15,
  "daysSinceLastOpen": 12,
  "currentStreak": 0,
  "longestStreak": 14,
  "dueCards": 18,
  "accountAgeDays": 90
}
```

### Output (~60 tokens) — structured JSON via tool use
```json
{
  "action": "motivate",
  "intervalDays": 4,
  "contentHint": "loss_aversion"
}
```

### Action values
| Value | Meaning |
|-------|---------|
| `send` | Use existing rule-based `NotificationTypeSelector` |
| `pause` | Skip send; set `suppressedUntil = now + intervalDays` |
| `motivate` | Send `MOTIVATION` type; pass `contentHint` to content builder |

### Content hints → template map
AI never generates copy. It picks one of these angles:

| `contentHint` | Emotional angle | Example copy |
|---------------|-----------------|--------------|
| `loss_aversion` | "You'll lose what you built" | "You had a 14-day streak. Don't let it fade." |
| `curiosity` | "Something interesting is waiting" | "18 words are waiting. Some you've never gotten right." |
| `social_proof` | "Others are moving forward" | "Learners like you reviewed 3x more this week." |
| `fresh_start` | "It's never too late to restart" | "Every expert was once a beginner. Start again today." |
| `achievement` | "You're close to something" | "You're 2 words from your next milestone." |

### Cost estimate
- Per call: ~250 input + ~60 output tokens = ~$0.00008 (Haiku pricing)
- 10,000 users × 20% COLD/DORMANT × weekly refresh = 2,000 calls/week = ~$0.16/week
- **~$8/year at 10k users**

---

## Updated Dispatch Flow

### Nightly batch — `EngagementSegmentScheduler` (new)
```
for each user with active push token:
  1. EngagementSegmentService.computeSegment(userId) → segment
  2. Save segment → notification_schedule.engagement_segment
  3. if segment in [COLD, DORMANT]:
       if ai_decided_at IS NULL OR ai_decided_at < now() - 7 days:
         NotificationAiAdvisor.advise(userId) async (fire-and-forget, log failures)
         save ai_action, ai_interval_days, ai_content_hint, ai_decided_at
```

### Hourly dispatch — `SmartNotificationDispatcher` (modified)
```
for each scheduled user at current hour:
  1. suppressedUntil not passed → skip (unchanged)
  2. segment = HOT or WARM:
       → existing NotificationTypeSelector logic (unchanged)
       → suppressedUntil = null (daily)
  3. segment = COOLING:
       → existing NotificationTypeSelector logic
       → suppressedUntil = now + 2 days after send
  4. segment = COLD or DORMANT:
       ai_action = "send"     → existing NotificationTypeSelector
       ai_action = "pause"    → skip, suppressedUntil += ai_interval_days
       ai_action = "motivate" → type = MOTIVATION, contentHint passed to builder
       → suppressedUntil = now + ai_interval_days after send
  5. Open recorded (recordOpen):
       → segment reset to WARM
       → ai_decided_at = null (invalidate cache, re-evaluate next night)
```

---

## Schema Changes — V35 Migration

```sql
ALTER TABLE notification_schedule
  ADD COLUMN engagement_segment  VARCHAR(20)  NOT NULL DEFAULT 'WARM',
  ADD COLUMN ai_action           VARCHAR(20),
  ADD COLUMN ai_interval_days    INT,
  ADD COLUMN ai_content_hint     VARCHAR(50),
  ADD COLUMN ai_decided_at       TIMESTAMP;
```

---

## New & Modified Components

| Component | Type | Change |
|-----------|------|--------|
| `EngagementSegmentService` | NEW service | Computes segment from `notification_log` history |
| `NotificationAiAdvisor` | NEW service | Haiku call → structured `AiAdvice` data class |
| `EngagementSegmentScheduler` | NEW scheduler | Nightly: segment compute + AI refresh for cold users |
| `NotificationSchedule` | entity | Add 5 new fields |
| `NotificationTypeSelector` | modify | Add `MOTIVATION` type |
| `NotificationContentBuilder` | modify | Add `MOTIVATION` case with hint→template map |
| `NotificationEngagementService` | modify | `recordOpen` resets segment to WARM, clears `ai_decided_at` |
| `SmartNotificationDispatcher` | modify | Branch on segment + `ai_action` |
| `V35__engagement_segment_ai_fields.sql` | migration | New columns |

---

## Data Classes

```kotlin
// NotificationAiAdvisor return type
data class AiAdvice(
    val action: String,         // "send" | "pause" | "motivate"
    val intervalDays: Int,
    val contentHint: String?    // null when action != "motivate"
)
```

---

## Error Handling

- AI call fails → log warning, fall back to segment-default rule-based behavior (no crash)
- AI returns unexpected JSON → log warning, fall back
- `ai_decided_at` stays null on failure → retry next nightly batch
- AI advisor failures must NEVER block the hourly dispatch

---

## What Does NOT Change

- `consecutiveIgnores` field remains — still secondary input to segment computation
- `NotificationTimingService` (optimal hour) — unchanged
- All existing `NotificationType` values — unchanged, `MOTIVATION` added as new value
- Existing `/notification/open` endpoint — unchanged
- User-facing frequency settings (`DAILY`/`EVERY_OTHER_DAY`/`WEEKLY`/`OFF`) — still respected as hard caps

---

## Out of Scope

- AI generating free-text notification copy (too costly, unpredictable length)
- Per-notification A/B testing
- User-visible "why am I getting this" explanations
- Real-time segment updates during dispatch (nightly batch is sufficient)
