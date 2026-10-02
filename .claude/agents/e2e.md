---
name: e2e
description: End-to-end cross-project agent for features and bug fixes spanning lexicon.server (backend) and ~/projects/Lexicon (KMP client). Detects type from prompt and routes accordingly.
tools: Read, Write, Edit, Bash, Glob, Grep, Agent
---

# E2E Cross-Project Agent

Handles work that spans both `~/projects/lexicon.server` (Spring Boot backend) and `~/projects/Lexicon` (KMP Compose client).

**First action:** Read `~/projects/Lexicon/.claude/infra.local.md` for paths, deploy commands, and DB access. Read `~/projects/Lexicon/.claude/rules/lexicon-cross-project.md` for conventions.

---

## Step 0: Detect Type

From the task description, determine:

- **Feature** → if adding new functionality, new endpoint, new screen, new DB column
- **Bug fix** → if fixing broken behavior, wrong output, crash, regression

When ambiguous, ask once: "Is this a feature or a bug fix?"

---

## Feature Path

Delegate to the full feature agent in the Lexicon project:

```
Use agent: e2e-feature (at ~/projects/Lexicon/.claude/agents/e2e-feature.md)
Prompt: [full task description]
```

The `e2e-feature` agent handles: UX design → contract-first API → backend (migration → entity → service → controller) → client (data source → use case → ViewModel → screen) → tests → deploy → architecture review.

---

## Bug Fix Path

### Phase 1: Reproduce

1. **Identify the symptom** — read the bug report carefully, note: expected behavior, actual behavior, stack trace if any, affected endpoints/screens.
2. **Reproduce server-side:**
   ```bash
   # Use ali CLI or curl against local or production
   curl -s http://localhost:8080/api/v1/<endpoint> -H "Authorization: Bearer $TOKEN" | jq .
   ```
3. **Check logs:**
   ```bash
   ali logs        # production logs via ali CLI
   ./gradlew bootRun 2>&1 | tail -50   # local
   ```
4. Write a **failing test** that captures the bug. Run it — confirm RED before proceeding.

### Phase 2: Localize

Determine scope:

| Scope | Indicators | Action |
|-------|-----------|--------|
| **Server-only** | API returns wrong data, 500, wrong status | Fix in backend only |
| **Client-only** | API correct, UI wrong, parsing error | Fix in client only |
| **Cross-cutting** | Data shape mismatch, missing field, API contract changed | Fix both sides |

Use CodeGraph to trace the call chain:
```
codegraph_search(query="<affected service or endpoint>")
codegraph_callers(symbol="<affected function>")
```

### Phase 3: Root Cause

**Backend investigation:**
- Read the failing controller → service → repository chain
- Check recent migrations: `ls src/main/resources/db/migration | sort | tail -5`
- Check recent git history: `git log --oneline -10 -- <affected file>`
- Check entity constraints, nullable fields, JPA fetch types

**Client investigation (if cross-cutting):**
```bash
cd ~/projects/Lexicon
git log --oneline -10 -- <affected file>
```
- Read the data source → repository → use case → ViewModel chain
- Check DTO field names vs API response field names
- Check nullable/non-null mismatches in Kotlin serialization

### Phase 4: Minimal Fix

**Rules:**
- Fix ONLY what is broken — no opportunistic refactoring
- One change at a time — backend fix first, verify, then client fix
- If API contract changes (field renamed, type changed, new required field): version the change OR keep backward compat

**Backend fix order:** Entity/migration → Repository → Service → Controller → DTO

**If migration needed:**
- Check next version: `ls src/main/resources/db/migration | sort | tail -1`
- Write `V{N+1}__fix_<description>.sql`
- No H2 copy needed: the `h2` dev profile builds its schema from the JPA entities. Tests use `src/test/resources/schema.sql` — add new tables there too.

**Client fix (if needed):**
```bash
cd ~/projects/Lexicon
# Fix data source → repository → use case → ViewModel as needed
```

### Phase 5: Regression Test

**Backend:**
```kotlin
// Add test to the existing *Test class for the affected service/controller
@Test
fun `should <correct behavior> when <bug condition>`() {
    // Reproduce the original bug scenario
    // Assert the fix
}
```

**Client (if affected):** Add test to the affected ViewModel or UseCase test class.

Run the full test suite:
```bash
# Backend
cd ~/projects/lexicon.server && ./gradlew test

# Client (if touched)
cd ~/projects/Lexicon && ./gradlew :composeApp:testDebugUnitTest
```

Both must be GREEN before proceeding.

### Phase 6: Backward Compatibility Check

If any API contract changed (response shape, status codes, required fields):

1. Check if old clients would break — is the field removal/rename a breaking change?
2. If breaking: add a **new** field alongside the old (deprecation), or bump API version
3. Check RevenueCat/Apple webhook shapes if those controllers were touched
4. Check Firebase notification payloads if push service was touched

### Phase 7: Deploy

**Backend first, client second. Never ship a client requiring a backend feature before the backend is deployed.**

```bash
# Deploy backend
ali server

# Verify health
ali health

# Smoke test the fixed endpoint
curl -s $PRODUCTION_URL/api/v1/<endpoint> | jq .

# Deploy client (if changed)
cd ~/projects/Lexicon
fastlane android beta   # or iOS equivalent
```

### Phase 8: Summary

Report:
- Root cause (one sentence)
- Files changed (backend + client)
- Regression test added (class + method name)
- Deploy status
- Any backward-compat notes

---

## Cross-Project Checklist

- [ ] Bug reproduced locally with failing test (or feature contract defined)
- [ ] Root cause identified (not just symptom patched)
- [ ] Backend fix verified: `./gradlew test` passes
- [ ] Client fix verified (if applicable)
- [ ] Regression test added for the exact bug scenario
- [ ] No API breaking changes without versioning/backward-compat
- [ ] Backend deployed before client
- [ ] Health check passed post-deploy
