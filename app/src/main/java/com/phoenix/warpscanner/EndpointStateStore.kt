package com.phoenix.warpscanner

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Step 1 of the endpoint state machine
 * (spec: scanner-endpoint-state-machine-spec.md, section 13 step 1).
 *
 * Step 1 scope, strictly: the store + timestamp updates + display strings.
 * States that can occur in step 1 are WARMING and ACTIVE only. Streak
 * counting, QUARANTINED/SUSPECT transitions, control-gated validity,
 * publish gating and the suspect prompt belong to steps 2 and 3 and
 * MUST NOT appear here. SILENT outcomes update nothing in step 1.
 *
 * Evidence rules (spec section 4):
 * - LANDED (verify handshake + DNS) -> working evidence: markWorking.
 * - APPEARED (shape candidate, verify not landed) -> alive evidence
 *   only: markSeen. Appearance never counts against an endpoint
 *   (APPEARS ⟹ NOT DEAD, user law 2026-10-26).
 */
enum class EndpointState { WARMING, ACTIVE }

data class EndpointStateRecord(
    val ip: String,
    val state: EndpointState,
    /** Last time the endpoint answered a shape probe or landed a verify. */
    val lastSeenAt: Long,
    /** Last time the endpoint landed a verify; null when never verified. */
    val lastWorkingAt: Long?
)

/** Persistent per-IP state records (simple JSON file, same pattern as BackupStore). */
object EndpointStateStore {

    private fun file(ctx: Context): File = File(ctx.filesDir, "endpoint_states.json")

    fun load(ctx: Context): Map<String, EndpointStateRecord> {
        val out = LinkedHashMap<String, EndpointStateRecord>()
        val f = file(ctx)
        if (!f.exists()) return out
        try {
            val arr = JSONArray(f.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val state = try {
                    EndpointState.valueOf(o.optString("state", "WARMING"))
                } catch (_: Exception) {
                    EndpointState.WARMING
                }
                out[o.getString("ip")] = EndpointStateRecord(
                    ip = o.getString("ip"),
                    state = state,
                    lastSeenAt = o.optLong("lastSeenAt", 0L),
                    lastWorkingAt = if (o.isNull("lastWorkingAt")) null else o.getLong("lastWorkingAt")
                )
            }
        } catch (_: Exception) { /* corrupted file -> start fresh */ }
        return out
    }

    private fun save(ctx: Context, records: Map<String, EndpointStateRecord>) {
        try {
            val arr = JSONArray()
            for (r in records.values) {
                arr.put(JSONObject().apply {
                    put("ip", r.ip)
                    put("state", r.state.name)
                    put("lastSeenAt", r.lastSeenAt)
                    if (r.lastWorkingAt != null) put("lastWorkingAt", r.lastWorkingAt) else put("lastWorkingAt", JSONObject.NULL)
                })
            }
            file(ctx).writeText(arr.toString())
        } catch (_: Exception) { /* best effort */ }
    }

    /** A verify landing: positive working evidence. Always lands in ACTIVE. */
    fun markWorking(ctx: Context, ip: String) {
        val now = System.currentTimeMillis()
        val records = load(ctx).toMutableMap()
        records[ip] = records[ip]?.copy(state = EndpointState.ACTIVE, lastSeenAt = now, lastWorkingAt = now)
            ?: EndpointStateRecord(ip, EndpointState.ACTIVE, now, now)
        save(ctx, records)
    }

    /** A shape appearance without a verify landing: alive evidence only. */
    fun markSeen(ctx: Context, ip: String) {
        val now = System.currentTimeMillis()
        val records = load(ctx).toMutableMap()
        records[ip] = records[ip]?.copy(lastSeenAt = now)
            ?: EndpointStateRecord(ip, EndpointState.WARMING, now, null)
        save(ctx, records)
    }

    /** Display string per IP for step-1 rows (Scan failed-row tags). */
    fun displayMap(ctx: Context): Map<String, String> =
        load(ctx).mapValues { (_, r) -> EndpointStateDisplay.displayFor(r) }
}

/** Display strings for step 1 (spec section 8, WARMING/ACTIVE only). */
object EndpointStateDisplay {

    fun displayFor(r: EndpointStateRecord): String = when (r.state) {
        EndpointState.WARMING -> "warming…"
        EndpointState.ACTIVE -> {
            val w = r.lastWorkingAt
            if (w == null) "warming…" else "last seen working " + relativeAgo(w)
        }
    }

    private fun relativeAgo(ts: Long): String {
        val diff = System.currentTimeMillis() - ts
        if (diff < 0) return "just now"
        return when {
            diff < 60_000 -> "just now"
            diff < 3_600_000 -> "${diff / 60_000}m ago"
            diff < 86_400_000 -> "${diff / 3_600_000}h ago"
            else -> "${diff / 86_400_000}d ago"
        }
    }
}
