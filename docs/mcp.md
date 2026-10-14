# MCP server: developer guide

Lexicon exposes a learner's own words and progress to AI apps (Claude, Claude Code, ChatGPT, …) over the
[Model Context Protocol](https://modelcontextprotocol.io). The learner signs in with the Google account they
use in the app, ticks what the AI app may do, and the app then calls tools on `POST /mcp`.

- User guide: https://alirezaiyan.com/lexicon/ai (source in the `alirezaiyan.com` repo, `src/app/lexicon/ai`)
- History and decisions: `Lexicon/.claude/plans/2026-10-14-lexicon-server-mcp.md`
- Code: `src/main/kotlin/com/alirezaiyan/vokab/server/mcp/`

**Status (2026-10-14):** built and deployed, **off in production** (`MCP_ENABLED` unset). See
[Enabling in production](#enabling-in-production).

## Ground rules

These are product rules, not style preferences. Breaking one is a bug.

1. **MCP never calls a paid AI API and never touches credits.** The AI app does the thinking on its own bill.
   `ModularityTest` fails if `mcp`, `words` or `analytics` depend on `ai` or `credits`.
2. **No plan gating, no per-user quotas.** Only features that cost us AI money are restricted. The only limits are
   sanity caps (100 words per call, field lengths) and an abuse ceiling of 300 calls/min per learner and app.
3. **Never record reviews or change levels.** A chat quiz is not a review. Only the app moves words through the SRS.
4. **No delete tool yet.** The server hard-deletes and deletions don't sync across devices, so a word deleted over
   MCP would come back from another device.
5. **`add_words` never goes through `WordService.upsert`.** Upserting a word that already exists resets its SRS
   progress. Use `WordAdditionService`.

## Request flow

```
AI app ──► /.well-known/oauth-protected-resource      who protects /mcp?
       ──► /.well-known/oauth-authorization-server     where do I sign in?
       ──► /oauth2/authorize ─► /oauth/login (Google via Firebase popup)
                              ─► /oauth/consent (pick scopes)
       ◄── redirect with code + iss
       ──► /oauth2/token (PKCE, resource=<issuer>/mcp) ─► access token (1 h) + refresh token (30 d, rotating)
       ──► POST /mcp  Authorization: Bearer …            tools/list, tools/call
```

Words written over MCP reach the app through the normal sync: the app pulls changed words when it resumes
(`RefreshWordsUseCase`, which uploads the pending review queue first because the pull is remote-wins).

## Security filter chains

Three chains in `McpAuthorizationServerConfig`, all present only when `spring.ai.mcp.server.enabled=true`.

| Order | Chain | Matches | Purpose |
|---|---|---|---|
| 1 | `mcpAuthorizationServerChain` | Spring Authorization Server endpoints (`/oauth2/*`, `/.well-known/oauth-authorization-server`, JWKS) | Issues MCP tokens |
| 2 | `mcpOAuthPagesChain` | `/oauth/**`, `/.well-known/oauth-protected-resource/**` | Sign-in page, session, consent page, client registration, resource metadata |
| 3 | `mcpResourceServerChain` | `/mcp`, `/mcp/**` | The MCP endpoint |

Filters on the `/mcp` chain, in order:

1. `McpOriginFilter`: if an `Origin` header is present it must be our issuer or listed in `MCP_ALLOWED_ORIGINS`,
   else 403 (DNS-rebinding guard). Native and server-side clients send no Origin and pass.
2. Bearer token validation: our own MCP signing key (never the app's JWT key), our issuer, audience
   `<issuer>/mcp`, and the grant must still exist in `oauth2_authorization` (so disconnect is immediate).
3. `McpScopeFilter`: for `tools/call`, looks up the tool's scope in `McpScopes.BY_TOOL`. Missing scope → 403 with
   `WWW-Authenticate: Bearer error="insufficient_scope", scope="…"` so the client can ask the user again (step-up).
4. `McpRateLimitFilter`: 300 calls/min per `sub:client_id`, then 429 with `Retry-After: 60`.

The 401 challenge advertises `resource_metadata` and the full scope list. Tools also re-check their scope via
`context.userIdWithScope(...)` (defence in depth). The user id and client id reach tools through
`McpTransportContext` (see `McpServerConfig`), not the `SecurityContext`.

## Authorization server

Embedded Spring Authorization Server 1.5.8, with these additions (all in `McpAuthorizationServerExtensions.kt`
and `McpRegisteredClients.kt`):

| Spec item | How |
|---|---|
| Client ID Metadata Documents (preferred client registration) | `McpRegisteredClientRepository`: an `https://` client_id is fetched (`HttpMcpClientMetadataFetcher`: public IPs only, no redirects, 5 KB cap, 3 s/5 s timeouts), validated, and cached for 24 h. Falls back to the stored copy if a refresh fails. |
| Dynamic Client Registration (deprecated, kept for older clients) | Our own `POST /oauth/register` (SAS 1.x has no open RFC 7591 endpoint). Public clients only, PKCE required. |
| Redirect URIs | https, or http on loopback only (`McpRedirectUris`). |
| Refresh tokens for public clients | SAS refuses these. `PublicClientRefreshAuthentication` + `McpRefreshTokenGenerator` add them; every refresh rotates the token. |
| `resource` (RFC 8707) | `McpResourceParameter`: must be `<issuer>/mcp` on authorize and token requests, else `invalid_target`. |
| `iss` in the authorization response (RFC 9207) | `McpAuthorizationResponses`. |
| `client_id` claim (RFC 9068) | Token customizer; used by the rate limit and the audit log. |
| Consent | Our own page (`McpConsentPage`): app name, where it returns to, who publishes it, one checkbox per scope. No JavaScript, strict CSP. |

Sign-in (`/oauth/login`) is Google via the Firebase JS SDK and only accepts existing Lexicon accounts. Apple-only
accounts can't connect yet.

## Scopes and tools

| Scope | Consent label | Tools |
|---|---|---|
| `words:read` | See your words and tags | `search_words`, `list_tags`, `get_due_words` |
| `words:write` | Add words and tags | `add_words`, `tag_words` |
| `progress:read` | See your study progress | `get_difficult_words`, `get_weekly_report` |

`add_words` and `tag_words` write an `MCP_WRITE` row to the audit log (user, client id, tool, word ids only).

### Adding a tool

1. Write the method in `WordMcpTools` or `ProgressMcpTools` (or a new `@Component` in `mcp/`):
   ```kotlin
   @McpTool(
       name = "list_tags",
       description = "List the learner's tags with how many words each has.",
       title = "List my Lexicon tags",
       annotations = McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false),
       generateOutputSchema = true,
   )
   fun listTags(context: McpTransportContext): TagList = TagList(
       tagService.list(context.userIdWithScope(McpScopes.WORDS_READ)).map { TagResult(it.id, it.name, it.wordCount) }
   )
   ```
   - Always set `title` and all three hints. The library defaults are `openWorldHint = true` and
     `destructiveHint = true`, which is wrong for every tool we have.
   - Write tools: `readOnlyHint = false`, `destructiveHint = false` unless it really destroys data,
     `idempotentHint` if repeating it is harmless.
2. Add the tool to `McpScopes.BY_TOOL`. A tool missing from the map is rejected by `McpScopeFilter`.
3. Output: return an MCP-only DTO, never a domain entity or an app API DTO.
   - Wrap lists in an object (`WordList(words)`); structured output must be an object.
   - The schema generator ignores Kotlin nullability. Nullable fields need `@JsonInclude(NON_NULL)` on the class and
     `@field:JsonProperty(required = false)`, or schema validation fails when the value is null.
4. Input: tool params are parsed by a Jackson mapper **without** the Kotlin module. Input data classes need
   `@JsonCreator`/`@JsonProperty`, and optional fields need `@field:JsonProperty(required = false)`.
5. Validate sizes with `require(...)` (it becomes a tool error the model can read). Do filtering in the database,
   not in memory.
6. Call only `words`, `analytics`, `user` services (see rule 1). Write tools log via `auditLog.logMcpWrite`.
7. Test it in `McpServerIntegrationTest` (see below), including the wrong-scope case.
8. Update the user guide's "What you can allow" list if the tool changes what a scope means.

## Data

| Table | Holds |
|---|---|
| `oauth2_registered_client` | MCP clients. `client_id` is up to 1000 chars (V50) because CIMD ids are URLs. |
| `oauth2_authorization` | Grants and tokens. `principal_name` is the user id. |
| `oauth2_authorization_consent` | Remembered consent per user and client. |

- `McpConnectionService` lists live grants for `GET /api/v1/mcp/connections` and deletes them on
  `DELETE /api/v1/mcp/connections/{id}`. These two endpoints are always on (app JWT auth) so the app's
  Settings → Connected apps works whatever the MCP flag says.
- `UserDataPurger` deletes a user's grants and consents on account deletion.
- `McpGrantCleanup` (00:40 UTC nightly) deletes grants whose every token expired over a day ago, and clients older
  than 7 days with no grants or consents.

## Configuration

| Env var | Default | Notes |
|---|---|---|
| `MCP_ENABLED` | `false` | Turns on `/mcp`, the OAuth endpoints and all MCP beans. |
| `MCP_ISSUER` | `http://localhost:8080` | Public origin. Token issuer, base of every OAuth URL, and audience is `<issuer>/mcp`. |
| `MCP_SIGNING_KEY_PATH` | `./keys/mcp-signing-key.json` | RSA JWK, created on first start. Must persist across deploys or every connection breaks. |
| `MCP_ALLOWED_ORIGINS` | empty | Extra browser origins allowed to call `/mcp`. |
| `MCP_FIREBASE_WEB_API_KEY`, `MCP_FIREBASE_AUTH_DOMAIN`, `MCP_FIREBASE_PROJECT_ID` | empty | Public Firebase web config for the sign-in page. |

Token lifetimes (`app.mcp.access-token-minutes` = 60, `app.mcp.refresh-token-days` = 30) live in `AppProperties.McpConfig`.

## Running locally

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) MCP_ENABLED=true ./scripts/start-dev.sh
claude mcp add --transport http lexicon-local http://localhost:8080/mcp
# then /mcp in Claude Code → lexicon-local → sign in
```

The Firebase variables must be set for the sign-in page to work. For quick tool checks the MCP Inspector
(`npx @modelcontextprotocol/inspector`) works against the same URL.

## Testing

All MCP tests live in `src/test/kotlin/.../mcp/` and need Docker (Testcontainers Postgres).

- `McpTestTokens`: mints a real MCP access token and grant for a user and scope set, so tool tests skip the browser
  flow. Use the shared `MCP_ENABLED`, `MCP_TEST_ISSUER_PROPERTY`, `MCP_TEST_KEY_PROPERTY` properties so every MCP
  test reuses one Spring context.
- `McpServerIntegrationTest`: tools over real HTTP JSON-RPC.
- `McpOAuthFlowTest`: the whole OAuth flow. Registration and sign-in are rate-limited per IP, so requests use
  `withFreshIp()` (a new `X-Forwarded-For` each call). Fetch the consent URL with `get(URI(...))`, or MockMvc
  encodes it twice. The metadata fetcher is a `@MockitoBean`.
- `McpConnectionsTest`: app endpoints, using real app JWTs (the `authentication()` post-processor doesn't reach
  the full filter chain).
- `McpRegisteredClientRepositoryTest`, `McpRateLimitFilterTest`, `McpGrantCleanupTest`: units.

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew test --tests '*.mcp.*'
```

Changing an endpoint shape? Regenerate the contract: `./gradlew test --tests '*OpenApiContractTest' -PupdateOpenApi=true`.

## Enabling in production

1. Caddy: route `/mcp`, `/oauth/*`, `/oauth2/*` and `/.well-known/oauth-*` on `lexicon.alirezaiyan.com` to :8080.
   Today only `/api/*` goes to the server, so these fall through to the web app.
2. VPS `.env`: `MCP_ENABLED=true`, `MCP_ISSUER=https://lexicon.alirezaiyan.com`,
   `MCP_SIGNING_KEY_PATH=/app/keys/mcp-signing-key.json` (on a persistent volume), the three `MCP_FIREBASE_*` values.
3. Firebase console: add `lexicon.alirezaiyan.com` to Authorized domains (the sign-in popup).
4. Secure session cookie: behind Caddy Spring sees plain http. Set `server.forward-headers-strategy=framework`
   (or `server.servlet.session.cookie.secure=true`).
5. Deploy (`ali server`), then connect from Claude Code and claude.ai with a real account and walk the whole flow:
   connect, each tool, a scope you didn't grant, disconnect from the app.
6. Deploy the website (`ali site`) so the user guide and the privacy section go live.

## Operations

- Who connected what: `GET /api/v1/mcp/connections` as the user, or query `oauth2_authorization` joined to
  `oauth2_registered_client`.
- What an app wrote: audit log rows with type `MCP_WRITE`.
- Revoke everything for one app: delete its `oauth2_authorization` rows. The next call gets 401.
- Rotating the signing key logs out every connected app. Avoid it unless the key leaked.

## Spec status

Targets MCP **2026-07-28** where the SDK allows. We run MCP Java SDK 0.18.4 via Spring AI 1.1.8, which speaks
protocol 2025-11-25. The stateless 2026-07-28 transport (`server/discover`, no `initialize`) needs a Java SDK that
supports it. None did on 2026-10-14, and the upgrade also needs Spring AI 2.x, which needs Spring Boot 4.
Check again before claiming full compliance.
