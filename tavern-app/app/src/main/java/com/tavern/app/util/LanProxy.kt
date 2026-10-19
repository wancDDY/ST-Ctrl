package com.tavern.app.util

import android.util.Log
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * TCP port-forward proxy with token-based access control.
 *
 * Listens on 0.0.0.0:[listenPort].  Every incoming HTTP request is inspected:
 *   - If the URL query parameter `?t=<validToken>` is present, or the cookie
 *     `st_lan_token=<validToken>` is set → transparent forward to 127.0.0.1:8000.
 *   - Otherwise → a token-entry page is served, asking the user to enter the
 *     correct token.  On submit, the page sets a cookie and redirects to / so
 *     the user lands in the tavern.
 *
 * This avoids touching config.yaml `listen: true` (which triggers SSL + network
 * discovery code paths that crash the embedded Node runtime on Android).
 */
class LanProxy(
    private val listenPort: Int = 7999,
    private val targetHost: String = "127.0.0.1",
    private val targetPort: Int = 8000,
    private val tokenProvider: () -> String  // called for every request — token can rotate
) {
    companion object {
        private const val TAG = "LanProxy"
        private const val BUFFER_SIZE = 8192
        private const val LOGIN_LOGO_DATA_URI = "data:image/png;base64,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"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null

    // Rate limiting: per-IP → array of [failCount, firstFailTimestamp]
    private val rateLimitMap = ConcurrentHashMap<String, LongArray>()
    private val rateLimitMaxFails = 5
    private val rateLimitWindowMs = 60_000L

    fun start() {
        if (!running.compareAndSet(false, true)) {
            Log.w(TAG, "LanProxy already running on port $listenPort")
            return
        }
        scope.launch {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress("0.0.0.0", listenPort))
                serverSocket = ss
                com.tavern.app.log.TavernLog.i(TAG, "LAN 代理启动 port=$listenPort → $targetHost:$targetPort")
                Log.i(TAG, "LanProxy listening on 0.0.0.0:$listenPort → $targetHost:$targetPort")

                while (running.get()) {
                    val client = try { ss.accept() } catch (_: Exception) {
                        if (!running.get()) break
                        continue
                    }
                    launch { handleClient(client) }
                }
            } catch (e: Exception) {
                if (running.get()) Log.e(TAG, "LanProxy unexpected stop: ${e.message}")
            } finally {
                running.set(false)
                try { serverSocket?.close() } catch (_: Exception) {}
                serverSocket = null
            }
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        scope.cancel()
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        Log.i(TAG, "LanProxy stopped")
        com.tavern.app.log.TavernLog.i(TAG, "LAN 代理已关闭")
    }

    fun isRunning(): Boolean = running.get()

    // per-client handling

    private suspend fun handleClient(client: Socket) {
        try {
            val input = client.getInputStream()
            val headerBytes = ByteArrayOutputStream()
            var preReadBody = ByteArray(0)
            val buf = ByteArray(2048)
            var headerEnd = -1
            var prev4 = 0
            // Only timeout header phase (30s); forwarding must stay alive for SSE/long-poll
            withTimeout(30000L) {
                while (headerEnd < 0) {
                    val n = input.read(buf)
                    if (n == -1) break
                    for (i in 0 until n) {
                        val b = buf[i].toInt() and 0xFF
                        headerBytes.write(b)
                        // Cap header size to avoid memory exhaustion from a
                        // malicious client streaming junk before \r\n\r\n.
                        if (headerBytes.size() > 64 * 1024) {
                            client.close()
                            break
                        }
                        prev4 = (prev4 shl 8) or b
                        if (prev4 == 0x0D0A0D0A) {
                            headerEnd = headerBytes.size()
                            val remainder = n - i - 1
                            if (remainder > 0) preReadBody = buf.copyOfRange(i + 1, n)
                            break
                        }
                    }
                }
            }
            if (headerBytes.size() == 0) { client.close(); return }
            val headerStr = headerBytes.toString("UTF-8")
            val lines = headerStr.split("\r\n")
            val firstLine = lines.firstOrNull()?.trim() ?: return
            val headers = headerStr.substringAfter("\r\n")

            val token = tokenProvider()
            if (isRateLimited(client)) { serve429(client); return }

            if (isAuthorized(firstLine, headers, token)) {
                com.tavern.app.ApplicationState.lanSessionActive = true
                if (firstLine.startsWith("HEAD ")) {
                    try {
                        val out = client.getOutputStream()
                        val resp = "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nConnection: close\r\n\r\n"
                        out.write(resp.toByteArray()); out.flush()
                    } catch (_: Exception) {}
                } else {
                    forward(client, input, headerBytes, preReadBody)
                }
            } else {
                recordFailedAttempt(client)
                serveLoginPage(client, firstLine)
            }
        } catch (_: Exception) { /* normal disconnect */ }
        finally { try { client.close() } catch (_: Exception) {} }
    }

    /** Check query string or cookie for valid token. */
    private fun isAuthorized(firstLine: String, headers: String, token: String): Boolean {
        if (token.isBlank()) return false
        if (queryParamEquals(firstLine, "t", token)) return true
        val cookie = parseCookie(headers, "st_lan_token")
        return cookie == token
    }

    /** Compare a single query parameter value exactly (URL-decoded). */
    private fun queryParamEquals(requestLine: String, name: String, expected: String): Boolean {
        val parts = requestLine.split(" ")
        if (parts.size < 2) return false
        val target = parts[1]
        val qIdx = target.indexOf('?')
        if (qIdx < 0) return false
        val query = target.substring(qIdx + 1)
        for (kv in query.split("&")) {
            val eq = kv.indexOf('=')
            if (eq <= 0) continue
            if (kv.substring(0, eq) != name) continue
            val raw = kv.substring(eq + 1)
            val decoded = try {
                java.net.URLDecoder.decode(raw, "UTF-8")
            } catch (_: Exception) { raw }
            if (decoded == expected) return true
        }
        return false
    }

    /** Parse a single cookie value from the HTTP headers (anchored to the Cookie line). */
    private fun parseCookie(headers: String, name: String): String? {
        val regex = Regex("""(?m)^Cookie:\s*(?:[^;\r\n]*;)*\s*$name=([^;\s]+)""")
        return regex.find(headers)?.groupValues?.get(1)
    }

    // rate limiting

    private fun clientIp(client: Socket): String {
        return client.inetAddress.hostAddress ?: "unknown"
    }

    private fun isRateLimited(client: Socket): Boolean {
        val ip = clientIp(client)
        val now = System.currentTimeMillis()
        val entry = rateLimitMap.computeIfAbsent(ip) { LongArray(2) }
        synchronized(entry) {
            if (now - entry[1] > rateLimitWindowMs) {
                entry[0] = 0
                entry[1] = now
            }
            return entry[0] >= rateLimitMaxFails
        }
    }

    private fun recordFailedAttempt(client: Socket) {
        val ip = clientIp(client)
        val now = System.currentTimeMillis()
        val entry = rateLimitMap.computeIfAbsent(ip) { LongArray(2) }
        synchronized(entry) {
            if (now - entry[1] > rateLimitWindowMs) {
                entry[0] = 1
                entry[1] = now
            } else {
                entry[0]++
            }
        }
        sweepRateLimit(now)
    }

    // Prevent unbounded growth of the rate-limit map (sweep expired entries).
    private var lastSweep = 0L
    private fun sweepRateLimit(now: Long) {
        if (now - lastSweep < 60_000L) return
        lastSweep = now
        try {
            val it = rateLimitMap.entries.iterator()
            while (it.hasNext()) {
                val (_, entry) = it.next()
                synchronized(entry) {
                    if (now - entry[1] > rateLimitWindowMs) it.remove()
                }
            }
        } catch (_: Exception) {}
    }

    private fun serve429(client: Socket) {
        try {
            val body = "Too many failed attempts. Try again later."
            val response = "HTTP/1.1 429 Too Many Requests\r\n" +
                "Content-Type: text/plain\r\n" +
                "Content-Length: ${body.length}\r\n" +
                "Retry-After: 60\r\n" +
                "Connection: close\r\n\r\n" +
                body
            client.getOutputStream().write(response.toByteArray())
            client.getOutputStream().flush()
        } catch (_: Exception) {}
    }

    // transparent proxy

    private suspend fun forward(client: Socket, input: InputStream, headerBytes: ByteArrayOutputStream, preReadBody: ByteArray) =
        coroutineScope {
            val target = Socket()
            try {
                target.connect(InetSocketAddress(targetHost, targetPort), 3000)

                val out = target.getOutputStream()
                out.write(headerBytes.toByteArray())
                if (preReadBody.isNotEmpty()) out.write(preReadBody)
                out.flush()

                launch { relay(input, target.getOutputStream()) }
                launch { relay(target.getInputStream(), client.getOutputStream()) }
            } catch (_: Exception) {
                // Backend (Node) not reachable — tell the client instead of
                // leaving it with a bare connection reset / blank page.
                try { target.close() } catch (_: Exception) {}
                serve502(client)
            }
        }

    /** Copy bytes until EOF. */
    private fun relay(input: java.io.InputStream, output: OutputStream) {
        try {
            val buf = ByteArray(BUFFER_SIZE)
            var n: Int
            while (input.read(buf).also { n = it } != -1) {
                output.write(buf, 0, n)
                output.flush()
            }
        } catch (_: Exception) { /* connection closed */ }
        try { output.close() } catch (_: Exception) {}
    }

    /** 502 when the backend (Node) is not reachable. */
    private fun serve502(client: Socket) {
        try {
            val body = "Bad Gateway: backend not reachable"
            val response = "HTTP/1.1 502 Bad Gateway\r\n" +
                "Content-Type: text/plain\r\n" +
                "Content-Length: ${body.length}\r\n" +
                "Connection: close\r\n\r\n" +
                body
            client.getOutputStream().write(response.toByteArray())
            client.getOutputStream().flush()
        } catch (_: Exception) {}
    }

    // Token entry page

    private fun serveLoginPage(client: Socket, firstLine: String) {
        // Serve the token entry page for any unauthorized request.
        val html = buildLoginPage().toByteArray(Charsets.UTF_8)
        val response =
            "HTTP/1.1 200 OK\r\n" +
            "Content-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${html.size}\r\n" +
            "Connection: close\r\n\r\n"

        try {
            client.getOutputStream().write(response.toByteArray())
            client.getOutputStream().write(html)
            client.getOutputStream().flush()
        } catch (_: Exception) {}
    }

    private fun buildLoginPage(): String = """
<!DOCTYPE html>
<html lang="zh">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>ST Ctrl 局域网访问</title>
<link rel="icon" href="$LOGIN_LOGO_DATA_URI">
<style>
  :root {
    --bg: #ffffff;
    --card: #ffffff;
    --accent: #1a5fb4;
    --accent-2: #14508f;
    --text: #1b1b1b;
    --muted: #6b6b6b;
    --line: #c9c9c9;
    --error: #c01c28;
    color-scheme: light dark;
  }

  /* The page is reachable from any phone browser, so the dark palette is
     driven by the system preference instead of a fixed theme. */
  @media (prefers-color-scheme: dark) {
    :root {
      --bg: #161616;
      --card: #1c1c1c;
      --accent: #5b9bd5;
      --accent-2: #8ab8e6;
      --text: #e8e8e8;
      --muted: #9a9a9a;
      --line: #3a3a3a;
      --error: #e06c75;
    }
  }

  * { margin: 0; padding: 0; box-sizing: border-box; }

  body {
    background: var(--bg);
    color: var(--text);
    font-family: system-ui, -apple-system, 'Segoe UI', Roboto, sans-serif;
    display: flex;
    min-height: 100vh; min-height: 100dvh;
    padding: 24px 16px;
    overflow-y: auto;
    -webkit-tap-highlight-color: transparent;
  }

  .card {
    margin: auto;
    background: var(--card);
    border: 1px solid var(--line);
    border-radius: 8px;
    padding: 28px 20px;
    width: min(360px, 100%);
    text-align: center;
  }
  .card .icon {
    width: 48px; height: 48px; object-fit: contain;
    margin: 0 auto 12px;
  }

  h1 {
    font-size: 20px; font-weight: 600;
    margin-bottom: 6px;
  }
  .subtitle {
    font-size: 13px; color: var(--muted);
    margin-bottom: 24px;
  }

  .input-group {
    display: flex; gap: 0;
    border: 1px solid var(--line);
    border-radius: 6px; overflow: hidden;
    background: var(--card);
  }
  .input-group.focused { border-color: var(--accent); }
  .input-group.error   { border-color: var(--error); }
  .input-group.success { border-color: var(--accent); }

  input {
    flex: 1;
    background: transparent; border: none; outline: none;
    padding: 12px 14px;
    font-size: 16px; color: var(--text);
    font-family: inherit;
    min-width: 0;
  }
  input::placeholder { color: var(--muted); }

  button {
    background: var(--accent);
    color: #ffffff;
    border: none; padding: 12px 18px;
    font-size: 15px; font-weight: 600;
    font-family: inherit;
    cursor: pointer;
    white-space: nowrap;
  }
  button:hover { background: var(--accent-2); }

  .hint {
    font-size: 12px; color: var(--muted);
    margin-top: 16px; line-height: 1.6;
  }

  /* AGPL-3.0 section 13: network users must be offered the Corresponding
     Source, so the offer lives on the page every remote session passes through. */
  .source-offer {
    font-size: 12px; color: var(--muted);
    margin-top: 16px; padding-top: 16px;
    border-top: 1px solid var(--line);
  }
  .source-offer a { color: var(--accent); text-decoration: none; }
  .source-offer a:hover { text-decoration: underline; }

  .error-msg {
    font-size: 13px; color: var(--error);
    margin-top: 10px; min-height: 18px;
  }

  .success-overlay {
    position: fixed; inset: 0;
    background: var(--bg);
    /* Toggled by script via classList; only .show makes it visible. */
    display: none; align-items: center; justify-content: center;
    z-index: 10;
  }
  .success-overlay.show { display: flex; }
  .success-overlay p {
    font-size: 15px; color: var(--muted);
  }
</style>
</head>
<body>

<div class="card">
  <img class="icon" src="$LOGIN_LOGO_DATA_URI" alt="ST Ctrl">
  <h1>ST Ctrl</h1>
  <p class="subtitle">输入手机控制台显示的访问密钥</p>

  <div class="input-group" id="ig">
    <input type="text" id="tokenInput"
           placeholder="访问密钥" maxlength="32"
           autocomplete="off" autocorrect="off" autocapitalize="off" spellcheck="false">
    <button onclick="submitToken()">进入</button>
  </div>
  <div class="error-msg" id="err"></div>

  <div class="hint">密钥显示在手机 ST Ctrl 控制台</div>

  <div class="source-offer">
    本程序是 SillyTavern 的修改版，对应的完整源码：<a href="https://github.com/wancDDY/ST-Ctrl" target="_blank" rel="noopener">github.com/wancDDY/ST-Ctrl</a>
  </div>
</div>

<div class="success-overlay" id="overlay">
  <p>正在进入</p>
</div>

<script>
  var input = document.getElementById('tokenInput');
  var ig = document.getElementById('ig');
  var err = document.getElementById('err');
  var overlay = document.getElementById('overlay');

  input.addEventListener('focus', function() { ig.classList.add('focused'); });
  input.addEventListener('blur',  function() { ig.classList.remove('focused'); });

  input.addEventListener('keydown', function(e) {
    if (e.key === 'Enter') submitToken();
  });

  function submitToken() {
    var val = input.value.trim();
    if (!val) { showError('请输入 Token'); return; }

    // Test the token with a t= parameter. A 200 response without the HTML
    // login page confirms that the proxy accepted the token.
    fetch('/?t=' + encodeURIComponent(val), { method: 'HEAD' })
      .then(function(res) {
        if (res.ok && res.headers.get('Content-Type') &&
            res.headers.get('Content-Type').indexOf('text/html') === -1) {
          // Success — token is valid; set cookie and redirect
          document.cookie = 'st_lan_token=' + val + '; Path=/; SameSite=Lax';
          overlay.classList.add('show');
          setTimeout(function() { window.location.href = '/'; }, 800);
        } else {
          showError('Token 不正确');
        }
      })
      .catch(function() {
        // Network error — the token cannot be verified. Do NOT treat it as
        // success (a dead connection means nothing was forwarded); show an
        // error instead of falsely "passing" the token check.
        showError('验证失败，请检查网络后重试');
      });
  }

  function showError(msg) {
    err.textContent = msg;
    ig.classList.add('error');
    input.value = '';
    input.focus();
    setTimeout(function() { ig.classList.remove('error'); }, 600);
  }
</script>
</body>
</html>
""".trimIndent()
}
