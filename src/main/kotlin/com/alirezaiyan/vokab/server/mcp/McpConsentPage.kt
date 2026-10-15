package com.alirezaiyan.vokab.server.mcp

import org.springframework.web.util.HtmlUtils

/**
 * The consent screen. Shows the app's self-chosen name next to where it will send the learner
 * back, which the app can't fake: a client calling itself "Claude" still has to name its real
 * redirect host. Every value is HTML-escaped; the page has no script.
 */
internal object McpConsentPage {

    /** [publisher]: the site a metadata-document client publishes from (verified by fetching it), if any. */
    data class Client(val name: String, val returnHosts: List<String>, val publisher: String? = null)

    fun render(
        client: Client,
        clientId: String,
        state: String,
        requestedScopes: List<String>,
        authorizePath: String,
    ): String {
        val name = escape(client.name)
        val publisher = client.publisher?.let { "<p>Published by <strong>${escape(it)}</strong>.</p>" }.orEmpty()
        val hosts = client.returnHosts.joinToString(", ") { "<strong>${escape(it)}</strong>" }
        val scopes = requestedScopes.filter { it in McpScopes.ALL }.joinToString("\n") { scope ->
            val checked = if (scope in McpScopes.OPT_IN) "" else " checked"
            """<label><input type="checkbox" name="scope" value="${escape(scope)}"$checked> """ +
                """${escape(describe(scope))}</label>"""
        }
        val hidden = """<input type="hidden" name="client_id" value="${escape(clientId)}">""" +
            """<input type="hidden" name="state" value="${escape(state)}">"""
        return TEMPLATE
            .replace("__NAME__", name)
            .replace("__HOSTS__", hosts)
            .replace("__PUBLISHER__", publisher)
            .replace("__SCOPES__", scopes)
            .replace("__HIDDEN__", hidden)
            .replace("__ACTION__", escape(authorizePath))
    }

    private fun describe(scope: String) = when (scope) {
        McpScopes.WORDS_READ -> "See your words and tags"
        McpScopes.WORDS_WRITE -> "Add words and tags (never deletes or changes your progress)"
        McpScopes.PROGRESS_READ -> "See your study progress"
        McpScopes.WORDS_DELETE -> "Delete words (removes them from all your devices)"
        else -> scope
    }

    private fun escape(value: String) = HtmlUtils.htmlEscape(value, Charsets.UTF_8.name())

    const val CSP = "default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; frame-ancestors 'none'"

    private val TEMPLATE = """
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Allow access to Lexicon</title>
<style>
  :root { --bg: #f7f5fb; --card: #ffffff; --text: #1d1b20; --muted: #625b71; --accent: #6750a4; --line: #e7e0ec; }
  @media (prefers-color-scheme: dark) {
    :root { --bg: #141218; --card: #1d1b20; --text: #e6e0e9; --muted: #cac4d0; --accent: #d0bcff; --line: #49454f; }
  }
  * { box-sizing: border-box; }
  body { margin: 0; min-height: 100vh; display: grid; place-items: center; padding: 16px;
         background: var(--bg); color: var(--text); font: 16px/1.5 system-ui, -apple-system, sans-serif; }
  main { width: 100%; max-width: 420px; background: var(--card); border-radius: 24px; padding: 32px 24px;
         box-shadow: 0 1px 3px rgba(0,0,0,.08); }
  h1 { font-size: 22px; margin: 0 0 8px; overflow-wrap: anywhere; }
  p { color: var(--muted); margin: 0 0 16px; overflow-wrap: anywhere; }
  fieldset { border: 1px solid var(--line); border-radius: 16px; padding: 8px 16px; margin: 0 0 24px; }
  label { display: flex; gap: 12px; align-items: flex-start; padding: 8px 0; }
  input[type=checkbox] { margin-top: 4px; accent-color: var(--accent); }
  button { width: 100%; padding: 14px 16px; border: 0; border-radius: 999px; font: inherit; font-weight: 600; cursor: pointer; }
  .allow { background: var(--accent); color: var(--card); }
  .cancel { background: transparent; color: var(--accent); margin-top: 8px; }
</style>
</head>
<body>
<main>
  <h1>__NAME__ wants to use your Lexicon</h1>
  __PUBLISHER__
  <p>After you choose, you'll go back to __HOSTS__. Only allow apps you trust; you can disconnect them anytime in Lexicon's Settings.</p>
  <form method="post" action="__ACTION__">
    __HIDDEN__
    <fieldset>
__SCOPES__
    </fieldset>
    <button class="allow" type="submit">Allow</button>
  </form>
  <form method="post" action="__ACTION__">
    __HIDDEN__
    <button class="cancel" type="submit">Cancel</button>
  </form>
</main>
</body>
</html>
""".trimIndent()
}
