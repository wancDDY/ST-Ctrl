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
 *   - Otherwise → a beautiful animated token-entry page is served, asking the
 *     user to enter the correct token.  On submit, the page sets a cookie and
 *     redirects to / so the user lands in the tavern.
 *
 * This avoids touching config.yaml `listen: true` (which triggers SSL + network
 * discovery code paths that crash nodejs-mobile on Android).
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

    // ── per-client handling ────────────────────────────────────────

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
        if (firstLine.contains("?t=$token") || firstLine.contains("&t=$token")) return true
        val cookie = parseCookie(headers, "st_lan_token")
        return cookie == token
    }

    /** Parse a single cookie value from the HTTP headers (anchored to the Cookie line). */
    private fun parseCookie(headers: String, name: String): String? {
        val regex = Regex("""(?m)^Cookie:\s*(?:[^;\r\n]*;)*\s*$name=([^;\s]+)""")
        return regex.find(headers)?.groupValues?.get(1)
    }

    // ── rate limiting ──────────────────────────────────────────────

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

    // ── transparent proxy ──────────────────────────────────────────

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

    // ── beautiful animated login page ──────────────────────────────

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
<title>🍺 酒馆 Token 验证</title>
<style>
  :root {
    --bg: #08080e;
    --card: #0e0e16;
    --accent: #d4a853;
    --accent-dim: #8b6914;
    --text: #f0ede0;
    --muted: #8a8a80;
    --error: #cc4455;
    --success: #5aa87a;
    --purple: #6b5b9e;
    --radius: 16px;
  }

  * { margin: 0; padding: 0; box-sizing: border-box; }

  body {
    background: var(--bg);
    color: var(--text);
    font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
    display: flex; align-items: center; justify-content: center;
    min-height: 100vh; min-height: 100dvh;
    overflow: hidden;
    -webkit-tap-highlight-color: transparent;
  }

  /* ── animated background glow ── */
  .bg-glow {
    position: fixed; inset: 0; pointer-events: none; z-index: 0;
  }
  .glow-orb {
    position: absolute; border-radius: 50%;
    filter: blur(120px);
    animation: breathe 4s ease-in-out infinite;
  }
  .glow-orb.a {
    width: 60vw; height: 60vw; max-width: 600px; max-height: 600px;
    background: var(--accent); opacity: 0.06;
    top: -10%; left: 20%;
    animation-delay: 0s;
  }
  .glow-orb.b {
    width: 40vw; height: 40vw; max-width: 400px; max-height: 400px;
    background: var(--purple); opacity: 0.06;
    bottom: -10%; right: 10%;
    animation-delay: -2s;
  }
  @keyframes breathe {
    0%, 100% { transform: scale(1); }
    50%      { transform: scale(1.25); }
  }

  /* ── card ── */
  .card {
    position: relative; z-index: 1;
    background: var(--card);
    border: 1px solid rgba(212,168,83,0.12);
    border-radius: var(--radius);
    padding: 40px 32px;
    width: min(400px, 90vw);
    text-align: center;
    box-shadow: 0 0 80px rgba(212,168,83,0.04), 0 8px 32px rgba(0,0,0,0.5);
  }
  .card .icon {
    font-size: 48px; margin-bottom: 8px;
    animation: float 3s ease-in-out infinite;
  }
  @keyframes float {
    0%, 100% { transform: translateY(0); }
    50%      { transform: translateY(-8px); }
  }

  h1 {
    font-size: 20px; font-weight: 600;
    letter-spacing: 1px; margin-bottom: 6px;
  }
  .subtitle {
    font-size: 13px; color: var(--muted);
    margin-bottom: 28px;
  }

  /* ── input group ── */
  .input-group {
    display: flex; gap: 0;
    border: 1px solid rgba(212,168,83,0.2);
    border-radius: 12px; overflow: hidden;
    background: rgba(255,255,255,0.03);
    transition: border-color 0.3s;
  }
  .input-group.focused { border-color: var(--accent); }
  .input-group.error   { border-color: var(--error); animation: shake 0.5s ease; }
  .input-group.success { border-color: var(--success); }

  @keyframes shake {
    0%, 100% { transform: translateX(0); }
    20%      { transform: translateX(-8px); }
    40%      { transform: translateX(8px); }
    60%      { transform: translateX(-6px); }
    80%      { transform: translateX(4px); }
  }

  input {
    flex: 1;
    background: transparent; border: none; outline: none;
    padding: 14px 16px;
    font-size: 15px; color: var(--text);
    font-family: 'SF Mono', 'Cascadia Code', 'Consolas', monospace;
    letter-spacing: 2px;
    min-width: 0;
  }
  input::placeholder { color: var(--muted); letter-spacing: 0; }

  button {
    background: var(--accent);
    color: #0a0a12;
    border: none; padding: 14px 20px;
    font-size: 14px; font-weight: 600;
    cursor: pointer;
    letter-spacing: 1px;
    transition: background 0.2s, transform 0.1s;
    white-space: nowrap;
  }
  button:active { transform: scale(0.95); }
  button:hover  { background: #e0b85a; }

  .hint {
    font-size: 11px; color: var(--muted);
    margin-top: 20px; line-height: 1.6;
  }
  .hint span { color: var(--accent-dim); }

  .error-msg {
    font-size: 12px; color: var(--error);
    margin-top: 10px; min-height: 18px;
    transition: opacity 0.3s;
  }

  /* ── success overlay ── */
  .success-overlay {
    position: fixed; inset: 0;
    background: var(--bg);
    display: flex; align-items: center; justify-content: center;
    z-index: 10; opacity: 0; pointer-events: none;
    transition: opacity 0.4s;
  }
  .success-overlay.show { opacity: 1; pointer-events: auto; }
  .success-msg {
    text-align: center;
    font-size: 48px;
    animation: popIn 0.5s ease;
  }
  .success-msg p {
    font-size: 16px; color: var(--muted); margin-top: 12px;
  }
  @keyframes popIn {
    0%   { transform: scale(0.5); opacity: 0; }
    80%  { transform: scale(1.05); }
    100% { transform: scale(1); opacity: 1; }
  }
</style>
</head>
<body>

<div class="bg-glow">
  <div class="glow-orb a"></div>
  <div class="glow-orb b"></div>
</div>

<div class="card">
  <div class="icon">🍺</div>
  <h1>ST-Ctrl 酒馆</h1>
  <p class="subtitle">受 Token 保护 · 请输入访问密钥</p>

  <div class="input-group" id="ig">
    <input type="text" id="tokenInput"
           placeholder="输入 Token" maxlength="32"
           autocomplete="off" autocorrect="off" autocapitalize="off" spellcheck="false">
    <button onclick="submitToken()">进入</button>
  </div>
  <div class="error-msg" id="err"></div>

  <div class="hint">
    请从手机 ST-Ctrl 控制台获取访问地址<br>
    <span>Token 每次启动自动更换</span>
  </div>
</div>

<div class="success-overlay" id="overlay">
  <div class="success-msg">🍺<p>验证通过，即将进入酒馆…</p></div>
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

    // Test the token via fetch with t= param — if we get a 200
    // with no HTML login page, the proxy let us through.
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
