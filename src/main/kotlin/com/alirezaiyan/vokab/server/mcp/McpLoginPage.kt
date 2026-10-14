package com.alirezaiyan.vokab.server.mcp

import com.fasterxml.jackson.databind.ObjectMapper

/** The sign-in page an MCP client opens in the browser. Values reach the script JSON-encoded only. */
internal object McpLoginPage {

    private val json = ObjectMapper()

    fun render(apiKey: String, authDomain: String, projectId: String, sessionPath: String): String {
        val config = json.writeValueAsString(
            mapOf("apiKey" to apiKey, "authDomain" to authDomain, "projectId" to projectId)
        ).replace("<", "\\u003c")
        val session = json.writeValueAsString(sessionPath).replace("<", "\\u003c")
        return TEMPLATE
            .replace("__FIREBASE_CONFIG__", config)
            .replace("__SESSION_PATH__", session)
    }

    private val TEMPLATE = """
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Connect to Lexicon</title>
<style>
  :root { --bg: #f7f5fb; --card: #ffffff; --text: #1d1b20; --muted: #625b71; --accent: #6750a4; --error: #b3261e; }
  @media (prefers-color-scheme: dark) {
    :root { --bg: #141218; --card: #1d1b20; --text: #e6e0e9; --muted: #cac4d0; --accent: #d0bcff; --error: #f2b8b5; }
  }
  * { box-sizing: border-box; }
  body { margin: 0; min-height: 100vh; display: grid; place-items: center; padding: 16px;
         background: var(--bg); color: var(--text); font: 16px/1.5 system-ui, -apple-system, sans-serif; }
  main { width: 100%; max-width: 400px; background: var(--card); border-radius: 24px; padding: 32px 24px;
         box-shadow: 0 1px 3px rgba(0,0,0,.08); text-align: center; }
  h1 { font-size: 24px; margin: 0 0 8px; }
  p { color: var(--muted); margin: 0 0 24px; }
  button { width: 100%; padding: 14px 16px; border: 0; border-radius: 999px; background: var(--accent);
           color: var(--card); font: inherit; font-weight: 600; cursor: pointer; }
  button:disabled { opacity: .6; cursor: progress; }
  #error { color: var(--error); margin: 16px 0 0; min-height: 1.5em; }
</style>
</head>
<body>
<main>
  <h1>Connect to Lexicon</h1>
  <p>Sign in with the Google account you use in Lexicon. Next you'll choose what the AI app may access.</p>
  <button id="google" type="button">Continue with Google</button>
  <p id="error" role="alert"></p>
</main>
<script src="https://www.gstatic.com/firebasejs/10.14.1/firebase-app-compat.js"></script>
<script src="https://www.gstatic.com/firebasejs/10.14.1/firebase-auth-compat.js"></script>
<script>
  (function () {
    var button = document.getElementById('google');
    var errorBox = document.getElementById('error');
    firebase.initializeApp(__FIREBASE_CONFIG__);

    function fail(message) { errorBox.textContent = message; button.disabled = false; }

    button.addEventListener('click', function () {
      button.disabled = true;
      errorBox.textContent = '';
      firebase.auth().signInWithPopup(new firebase.auth.GoogleAuthProvider())
        .then(function (result) { return result.user.getIdToken(); })
        .then(function (idToken) {
          return fetch(__SESSION_PATH__, {
            method: 'POST',
            credentials: 'same-origin',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ idToken: idToken })
          });
        })
        .then(function (response) {
          return response.json().then(function (body) { return { ok: response.ok, body: body }; });
        })
        .then(function (result) {
          firebase.auth().signOut();
          if (!result.ok) { fail(result.body.error || 'Sign-in failed. Try again.'); return; }
          if (result.body.redirectUrl) { window.location.assign(result.body.redirectUrl); }
          else { fail('Signed in. Go back to your AI app and connect again.'); }
        })
        .catch(function () { fail('Sign-in was cancelled or failed. Try again.'); });
    });
  })();
</script>
</body>
</html>
""".trimIndent()
}
