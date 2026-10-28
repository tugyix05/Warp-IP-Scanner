package com.phoenix.warpscanner

import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * Firebase Realtime Database signal for Phoenix VPN.
 *
 * After a successful endpoints.json publish, Scanner writes a tiny
 * "feed changed" stamp here. Phoenix listens to this node and fetches
 * the GitHub feed immediately — no polling, no full-file download
 * on a timer.
 *
 * Design notes (2026-10-28, user spec):
 * - Signal ONLY, never content: Phoenix still takes the endpoint list
 *   from GitHub (tugyix05/Warp-IP-Scanner endpoints.json), so a forged
 *   signal can at most trigger one extra GitHub fetch, never inject
 *   endpoints.
 * - REST, not the Firebase SDK: Scanner has no google-services.json
 *   and needs none for this — one HTTPS PUT to the public signal node.
 * - Best-effort, fire-and-forget: a signal failure NEVER fails the
 *   publish itself (GitHubPush returns Ok regardless).
 *
 * SETUP REQUIRED BEFORE THIS DOES ANYTHING (console step, not code):
 * 1. Create a Realtime Database in project phoenix-vpn-70e45.
 * 2. Set rules so ONLY this node is openly readable/writable:
 *      { "rules": { "signals": { "feed": { ".read": true, ".write": true } } } }
 * 3. Fill RTDB_URL below with the database URL, e.g.
 *    https://phoenix-vpn-70e45-default-rtdb.asia-southeast1.firebasedatabase.app
 *    (region suffix depends on where the database was created).
 * If RTDB_URL were ever blank, write() is a no-op and publish works
 * exactly as before.
 */
object FeedSignal {

    // Created 2026-10-28 via Firebase console (locked mode,
    // Singapore asia-southeast1, Spark plan, no billing).
    // Must match Phoenix's FeedSignalListener.RTDB_URL exactly.
    private const val RTDB_URL =
        "https://phoenix-vpn-70e45-default-rtdb.asia-southeast1.firebasedatabase.app"

    /** Fire-and-forget signal write. Never throws, never blocks the caller. */
    fun write() {
        if (RTDB_URL.isBlank()) return
        Thread {
            try {
                val body = JSONObject().apply {
                    put("v", System.currentTimeMillis())
                }.toString()
                val c = (URL("$RTDB_URL/signals/feed.json").openConnection()
                    as HttpURLConnection).apply {
                    requestMethod = "PUT"
                    setRequestProperty("Content-Type", "application/json")
                    connectTimeout = 5000
                    readTimeout = 10000
                    doOutput = true
                }
                try {
                    c.outputStream.bufferedWriter().use { it.write(body) }
                    // Drain the response so the connection closes cleanly.
                    try { c.inputStream.bufferedReader().readText() } catch (_: Exception) { }
                } finally {
                    c.disconnect()
                }
            } catch (_: Exception) {
                // Signal is best-effort only — publish already succeeded.
            }
        }.start()
    }
}
