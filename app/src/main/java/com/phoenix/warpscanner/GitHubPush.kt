package com.phoenix.warpscanner

import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/**
 * Pushes endpoints.json to the Warp-IP-Scanner repo via the Lovable
 * publish relay, which forwards to the GitHub contents API.
 * Call off the main thread.
 *
 * The token is used only in the Authorization header and is never logged.
 * The relay stores no secret: it forwards the caller's token.
 */
object GitHubPush {

    private const val API =
        "https://ipmh.lovable.app/api/publish/repos/tugyix05/Warp-IP-Scanner/contents/endpoints.json"

    sealed class Result {
        object Ok : Result()
        data class Err(val reason: String) : Result()
    }

    /**
     * Feed URL fields (v1.32): every published endpoints.json must carry
     * these, or the companion VPN app loses its remotely-changeable links.
     * Defaults = the values the VPN app hardcodes today; the live file's
     * values win whenever they exist (see [withLiveUrls]).
     */
    const val DEFAULT_GENERATOR_URL = "https://pguard.val.run/"
    const val DEFAULT_UPDATE_URL = "https://ipmh.lovable.app/api/public/apk/phoenix-vpn-v18"

    /** The live endpoints.json as it stands on GitHub right now. */
    private class CurrentFile(
        val sha: String?,
        val generatorUrl: String,
        val updateUrl: String
    )

    fun push(token: String, json: String): Result {
        // One retry on timeout — mobile networks stall transiently.
        var attempt = 0
        while (true) {
            try {
                val current = getCurrent(token) // sha null when the file doesn't exist yet
                // Merge the LIVE url fields over the built JSON, so a publish
                // never resets a changed link back to the defaults.
                return put(token, withLiveUrls(json, current.generatorUrl, current.updateUrl), current.sha)
            } catch (e: SocketTimeoutException) {
                if (++attempt >= 2) return Result.Err("timed out — check connection")
            } catch (e: Exception) {
                return Result.Err(e.message ?: "network error")
            }
        }
    }

    /** Current blob sha + live feed URL fields, or defaults if the file doesn't exist yet (404). */
    private fun getCurrent(token: String): CurrentFile {
        val c = (URL(API).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/vnd.github+json")
            connectTimeout = 30000
            readTimeout = 60000
        }
        try {
            return when (c.responseCode) {
                200 -> {
                    val body = JSONObject(c.inputStream.bufferedReader().readText())
                    val live = try {
                        val raw = body.optString("content", "")
                        if (raw.isBlank()) JSONObject()
                        else JSONObject(String(Base64.decode(raw, Base64.DEFAULT), Charsets.UTF_8))
                    } catch (_: Exception) {
                        JSONObject()
                    }
                    CurrentFile(
                        body.optString("sha", null),
                        liveUrl(live, "generator_url", DEFAULT_GENERATOR_URL),
                        liveUrl(live, "update_url", DEFAULT_UPDATE_URL)
                    )
                }
                404 -> CurrentFile(null, DEFAULT_GENERATOR_URL, DEFAULT_UPDATE_URL)
                else -> throw Exception("GitHub GET failed: HTTP ${c.responseCode}")
            }
        } finally {
            c.disconnect()
        }
    }

    /** A live URL value, or the fallback when the key is missing/blank/null. */
    private fun liveUrl(obj: JSONObject, key: String, fallback: String): String {
        val v = obj.optString(key, "").trim()
        return if (v.isEmpty() || v == "null") fallback else v
    }

    /**
     * Overwrite the two feed URL fields in the outgoing JSON with the live
     * file's values. Never breaks a publish: on any failure the built JSON
     * is returned untouched (it already carries the defaults).
     */
    private fun withLiveUrls(json: String, generatorUrl: String, updateUrl: String): String {
        return try {
            JSONObject(json).apply {
                put("generator_url", generatorUrl)
                put("update_url", updateUrl)
            }.toString(2)
        } catch (_: Exception) {
            json
        }
    }

    private fun put(token: String, json: String, sha: String?): Result {
        val c = (URL(API).openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            doOutput = true
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("Content-Type", "application/json")
            connectTimeout = 30000
            readTimeout = 60000
        }
        try {
            val body = JSONObject().apply {
                put("message", "Update endpoints.json from scanner")
                put(
                    "content",
                    Base64.encodeToString(json.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                )
                if (sha != null) put("sha", sha)
            }.toString()
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            if (code == 200 || code == 201) {
                return Result.Ok
            }
            val errBody = try {
                c.errorStream?.bufferedReader()?.readText()?.take(300)
            } catch (_: Exception) {
                null
            }
            val reason = try {
                errBody?.let { JSONObject(it).optString("message", null) }
            } catch (_: Exception) {
                null
            } ?: "HTTP $code"
            return Result.Err(reason)
        } finally {
            c.disconnect()
        }
    }
}
