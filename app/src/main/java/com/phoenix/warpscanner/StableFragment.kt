package com.phoenix.warpscanner

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Stable tab: re-verifies every Backup IP (0% loss + WireGuard handshake)
 * in the background and ranks the 10 most stable by accumulated history.
 * Every run feeds [EndpointHistoryStore] (deduped: one recorded event per
 * IP per 30 minutes). Shown only when this tab is tapped — the Scan
 * tab flow is untouched. A manual ↻ REFRESH button (v1.18) re-measures ms
 * for the displayed top list so the user can check and confirm from this
 * tab — warm median values (v1.20), no re-ranking, no history, no dead handling.
 * v1.24: NOTHING auto-removes from Backup — failed draws are evidence
 * only (APPEARS ⟹ NOT DEAD, user law 2026-10-26); removal is by the
 * user's explicit per-row delete in the Backup tab.
 *
 * v1.22 views: the Source button switches between the Backup list and the
 * separate Test list ([TestStore]). Test runs measure exactly like Backup
 * runs (same rounds, same warm median) but never touch history and
 * never publish: the PUBLISH button is hidden on the Test view. Test
 * entries reach the feed only via Promote (Backup tab) or via
 * Publish-selected (tick rows in either view + preview + push).
 * (v1.24: neither view auto-removes any more — see above.)
 *
 * v1.26 compare mode (inside this tab, user decision 2026-10-27):
 * BACKUP + TEST sections in one list, per-row select, Publish-selected
 * over both sections, and one ↻ REFRESH that re-measures both stores.
 */
class StableFragment : Fragment() {

    private lateinit var engine: ScanEngine
    private lateinit var vm: ScanViewModel
    private lateinit var adapter: StableAdapter
    private lateinit var btnScan: Button
    private lateinit var btnCopyAll: Button
    private lateinit var btnPublish: Button
    private lateinit var btnPublishSelected: Button
    private lateinit var btnSource: Button
    private lateinit var btnRefresh: Button
    private lateinit var progress: ProgressBar
    private lateinit var tvStatus: TextView
    private lateinit var rowHead: LinearLayout
    private lateinit var tvEmpty: TextView
    private lateinit var tvDead: TextView
    private lateinit var rv: RecyclerView

    // Compare mode (v1.26): BACKUP + TEST sections in one list, inside
    // this tab. Selection reuses [selected]; refresh-checks-both and
    // publish-selected run on the compare lists below.
    private var compareMode = false
    private lateinit var compareAdapter: CompareAdapter
    private lateinit var btnCompare: Button
    private lateinit var layoutCompare: LinearLayout
    private lateinit var rvCompare: RecyclerView
    private lateinit var btnCompareRefresh: Button
    private lateinit var btnComparePublishSelected: Button
    private var compareBackup: List<ScanResult> = emptyList()
    private var compareTest: List<ScanResult> = emptyList()
    // Compare-refresh state, separate from single-source refresh state
    // on purpose: the two never interleave, and single-source refresh
    // logic stays untouched.
    private var comparePhase = 0 // 0 = BACKUP, 1 = TEST
    private var comparePass = 0
    private var compareRefreshOriginal: List<ScanResult> = emptyList()
    private var compareRefreshLanded: MutableMap<String, MutableList<ScanResult>> = mutableMapOf()
    private var compareWarmPass = 0
    private var compareWarmed: MutableSet<String> = mutableSetOf()
    private var compareUpdated = 0
    private var compareUnchanged = 0

    /** v1.22: false = Backup view, true = Test view. Never switched mid-run. */
    private var sourceIsTest = false

    /** Per-source displayed lists, so selection can span both views. */
    private var lastTopBackup: List<ScanResult> = emptyList()
    private var lastTopTest: List<ScanResult> = emptyList()

    /** Publish-selected ticks, keyed by endpoint; survives source switches. */
    private val selected: MutableSet<String> = mutableSetOf()

    @Volatile private var cancelled = false
    private var running = false

    // Multi-draw re-verify (v1.17): a Backup IP is only "dead" for the
    // run after failing every round — each round is a fresh engine
    // process (fresh flows) for the IPs that have not passed yet.
    private var round = 0
    private var roundBackup: List<BackupEntry> = emptyList()
    private var roundAlive: MutableList<ScanResult> =
        java.util.Collections.synchronizedList(mutableListOf())

    companion object {
        private const val MAX_VERIFY_ROUNDS = 4
        // Warm-median Refresh (v1.20): landed values needed per row, and
        // the per-row attempt cap (each attempt = one fresh engine pass).
        private const val REFRESH_LANDED_NEEDED = 3
        private const val REFRESH_MAX_ATTEMPTS = 7
        // v1.24 warm-up: attempts to land ONE handshake per row before
        // measuring. Warm-up draws are discarded — they never enter
        // the medians, so every measured value is warm by construction.
        private const val WARMUP_MAX_ATTEMPTS = 3
    }

    // Warm-median Refresh state: one fresh engine pass per round over the
    // rows still needing landed values; [refreshLanded] collects each
    // row's landed pass results in pass order.
    private var refreshPass = 0
    private var refreshOriginal: List<ScanResult> = emptyList()
    private var refreshLanded: MutableMap<String, MutableList<ScanResult>> = mutableMapOf()
    // v1.24 warm-up state: rows that landed at least one handshake.
    private var refreshWarmPass = 0
    private var refreshWarmed: MutableSet<String> = mutableSetOf()

    private var lastTop: List<ScanResult> = emptyList()
    private var lastDead: List<String> = emptyList()
    private var lastRemoved: Int = 0
    private var deadExpanded = false

    override fun onCreateView(inf: LayoutInflater, c: ViewGroup?, s: Bundle?): View? =
        inf.inflate(R.layout.fragment_stable, c, false)

    override fun onViewCreated(v: View, s: Bundle?) {
        engine = ScanEngine(requireContext())
        vm = ViewModelProvider(requireActivity())[ScanViewModel::class.java]
        adapter = StableAdapter(
            goodCount = {
                try { EndpointHistoryStore.goodCount(requireContext(), it) }
                catch (_: Exception) { 0 }
            },
            onExplore = { exploreRange(it) },
            isSelected = { selected.contains(it) },
            onToggleSelect = { ep, checked ->
                if (checked) selected.add(ep) else selected.remove(ep)
                updatePublishSelectedText()
            }
        )
        rv = v.findViewById(R.id.rvStable)
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = adapter

        btnScan = v.findViewById(R.id.btnStableScan)
        btnCopyAll = v.findViewById(R.id.btnCopyAll)
        btnPublish = v.findViewById(R.id.btnPublish)
        btnPublishSelected = v.findViewById(R.id.btnPublishSelected)
        btnSource = v.findViewById(R.id.btnSource)
        btnRefresh = v.findViewById(R.id.btnStableRefresh)
        progress = v.findViewById(R.id.pbStable)
        tvStatus = v.findViewById(R.id.tvStableStatus)
        rowHead = v.findViewById(R.id.rowStableHead)
        tvEmpty = v.findViewById(R.id.tvStableEmpty)
        tvDead = v.findViewById(R.id.tvDead)

        btnScan.setOnClickListener {
            if (running) {
                cancelled = true
                engine.cancel()
            } else {
                startReverify()
            }
        }
        btnCopyAll.setOnClickListener { copyAll() }
        btnPublish.setOnClickListener { publishTop10() }
        btnPublishSelected.setOnClickListener { publishSelected() }
        btnSource.setOnClickListener {
            if (running) toast("Stop the run first")
            else switchSource()
        }
        btnRefresh.setOnClickListener { startRefresh() }
        v.findViewById<TextView>(R.id.btnSettings).setOnClickListener { showSettingsDialog() }
        tvDead.setOnClickListener { toggleDead() }

        // v1.26 compare mode: same selection set as single-source view,
        // so ticks survive entering/exiting compare.
        compareAdapter = CompareAdapter(
            goodCount = {
                try { EndpointHistoryStore.goodCount(requireContext(), it) }
                catch (_: Exception) { 0 }
            },
            isSelected = { selected.contains(it) },
            onToggleSelect = { ep, checked ->
                if (checked) selected.add(ep) else selected.remove(ep)
                updateComparePublishText()
                updatePublishSelectedText()
            }
        )
        btnCompare = v.findViewById(R.id.btnCompare)
        layoutCompare = v.findViewById(R.id.layoutCompare)
        rvCompare = v.findViewById(R.id.rvCompare)
        rvCompare.layoutManager = LinearLayoutManager(requireContext())
        rvCompare.adapter = compareAdapter
        btnCompareRefresh = v.findViewById(R.id.btnCompareRefresh)
        btnComparePublishSelected = v.findViewById(R.id.btnComparePublishSelected)
        btnCompare.setOnClickListener { toggleCompare() }
        btnCompareRefresh.setOnClickListener { compareRefreshOrCancel() }
        btnComparePublishSelected.setOnClickListener { comparePublishSelected() }

        // v1.23: opened from Backup's "→ Stable" with the Test list.
        if (vm.pendingStableSourceIsTest) {
            vm.pendingStableSourceIsTest = false
            if (!sourceIsTest) switchSource()
        }
    }

    /**
     * Tabs are shown/hidden, not recreated (v1.23) — apply a pending
     * "→ Stable" request when this tab becomes visible again.
     */
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && ::vm.isInitialized && ::btnSource.isInitialized &&
            !compareMode && vm.pendingStableSourceIsTest
        ) {
            // Never eat the request while a run is active (same guard
            // as the Source button): keep it for the next tap.
            if (sourceIsTest) {
                vm.pendingStableSourceIsTest = false
            } else if (!running) {
                vm.pendingStableSourceIsTest = false
                switchSource()
            } else {
                toast("Stop the run first")
            }
        }
    }

    private fun updatePublishSelectedText() {
        if (!::btnPublishSelected.isInitialized) return
        btnPublishSelected.text =
            if (selected.isEmpty()) "⧉ PUBLISH SELECTED"
            else "⧉ PUBLISH SELECTED (${selected.size})"
    }

    /**
     * Switch the Stable view between Backup and Test. The displayed list,
     * title and Publish visibility follow the view; the selection survives
     * (that is how Publish-selected spans both views). Dead info is
     * per-run, so it resets until the next run in the new view.
     */
    private fun switchSource() {
        if (sourceIsTest) lastTopTest = lastTop else lastTopBackup = lastTop
        sourceIsTest = !sourceIsTest
        lastTop = if (sourceIsTest) lastTopTest else lastTopBackup
        adapter.items = lastTop
        lastDead = emptyList()
        lastRemoved = 0
        tvDead.visibility = View.GONE
        btnSource.text = if (sourceIsTest) "Source: Test" else "Source: Backup"
        btnPublish.visibility = if (sourceIsTest) View.GONE else View.VISIBLE
        val titleView = view?.findViewById<TextView>(R.id.tvStableTitle)
        titleView?.text = if (sourceIsTest) "Test Top 10" else "✓ Stable Top 10"
        if (lastTop.isEmpty()) {
            rowHead.visibility = View.GONE
            tvEmpty.visibility = View.VISIBLE
            tvEmpty.text = if (sourceIsTest) {
                "Test list is empty — scan with TEST checked in Range"
            } else {
                "Backup is empty — run a Scan first"
            }
        } else {
            rowHead.visibility = View.VISIBLE
            tvEmpty.visibility = View.GONE
        }
    }

    override fun onDestroyView() {
        try { engine.cancel() } catch (_: Exception) {}
        super.onDestroyView()
    }

    private fun toast(msg: String) =
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()

    private fun startReverify() {
        val ctx = requireContext()
        // v1.22: the "backup" list below is the active source list — Backup
        // or Test. Everything downstream (finish) branches on sourceIsTest.
        val backup = if (sourceIsTest) TestStore.load(ctx) else BackupStore.load(ctx)
        if (backup.isEmpty()) {
            tvEmpty.visibility = View.VISIBLE
            rowHead.visibility = View.GONE
            tvDead.visibility = View.GONE
            tvStatus.text = ""
            tvEmpty.text = if (sourceIsTest) {
                "Test list is empty — scan with TEST checked in Range"
            } else {
                "Backup is empty — run a Scan first"
            }
            return
        }
        if (!WgConfStore.exists(ctx)) {
            toast("Import your WARP .conf first")
            return
        }
        cancelled = false
        running = true
        deadExpanded = false
        btnScan.text = "■ CANCEL"
        progress.visibility = View.VISIBLE
        progress.max = backup.size
        progress.progress = 0
        tvEmpty.visibility = View.GONE
        rowHead.visibility = View.GONE
        tvDead.visibility = View.GONE
        tvStatus.text = "Re-verifying 0/${backup.size}…"

        val targets = backup.map { ScanResult(it.ip, it.port, null, 0) }
        roundBackup = backup
        roundAlive = java.util.Collections.synchronizedList(mutableListOf())
        round = 1
        runRound(targets)
    }

    /**
     * One verify round: a fresh engine process (fresh flows) over
     * [targets]. IPs that pass join [roundAlive]; the failures are
     * re-drawn in the next round, up to [MAX_VERIFY_ROUNDS] rounds.
     * An IP is alive for the run if ANY round passes it — a failed
     * draw says something about the flow, not the endpoint.
     */
    private fun runRound(targets: List<ScanResult>) {
        val confPath = WgConfStore.path(requireContext())
        engine.scan(
            ScanHelper.verifyArgs(targets, confPath),
            onResult = { r ->
                roundAlive.add(r)
                activity?.runOnUiThread {
                    if (cancelled) return@runOnUiThread
                    progress.progress = roundAlive.size
                    val base = "Re-verifying ${roundAlive.size}/${roundBackup.size}…"
                    tvStatus.text =
                        if (round > 1) "$base · round $round/$MAX_VERIFY_ROUNDS" else base
                }
            },
            onProgress = {},
            onDone = { ok, err ->
                // Capture the activity reference: the fragment may be gone.
                val act = activity ?: return@scan
                if (cancelled || !ok) {
                    act.runOnUiThread {
                        finish(act, ok, err, roundBackup, roundAlive.toList())
                    }
                    return@scan
                }
                val passed = roundAlive.map { it.endpoint }.toSet()
                val remaining = targets.filter { it.endpoint !in passed }
                if (remaining.isEmpty() || round >= MAX_VERIFY_ROUNDS) {
                    act.runOnUiThread {
                        finish(act, true, null, roundBackup, roundAlive.toList())
                    }
                } else {
                    round++
                    act.runOnUiThread {
                        if (!cancelled) {
                            tvStatus.text =
                                "Round $round/$MAX_VERIFY_ROUNDS — re-drawing ${remaining.size}…"
                        }
                    }
                    if (!cancelled) runRound(remaining)
                }
            }
        )
    }

    private fun finish(
        act: android.app.Activity,
        ok: Boolean,
        err: String?,
        backup: List<BackupEntry>,
        alive: List<ScanResult>
    ) {
        running = false
        btnScan.text = "Stable IP Scanner"
        progress.visibility = View.GONE
        val ctx = act.applicationContext
        when {
            cancelled -> {
                tvStatus.text = "Stopped"
                return
            }
            !ok -> {
                tvStatus.text = "Error: ${err ?: "unknown"}"
                Toast.makeText(ctx, "Error: ${err ?: "unknown"}", Toast.LENGTH_SHORT).show()
                return
            }
        }

        val now = System.currentTimeMillis()
        val aliveByEp = alive.associateBy { it.endpoint }
        // Backup runs feed the long-term memory (deduped inside the store:
        // at most one recorded event per IP per 30 minutes). Test runs
        // (v1.22) touch neither history: test data stays visible for
        // comparison, and only the ms refresh writes back — to the test
        // store, never to Backup. History records the run's events, but
        // v1.24 removed the auto-remove consumer: no run deletes
        // anything, on either view.
        for (b in backup) {
            val r = aliveByEp[b.endpoint]
            if (sourceIsTest) {
                if (r != null) {
                    TestStore.add(ctx, BackupEntry(b.ip, b.port, r.latencyMs, r.jitterMs, now))
                    // Step 1 (state machine): re-verify landing = working evidence.
                    EndpointStateStore.markWorking(ctx, b.ip)
                }
            } else if (r != null) {
                EndpointHistoryStore.recordStableResult(ctx, b.endpoint, r.latencyMs, r.jitterMs, alive = true)
                // Refresh Backup's ms/jitter with the fresh measurement (keeps min ms).
                BackupStore.add(ctx, BackupEntry(b.ip, b.port, r.latencyMs, r.jitterMs, now))
                // Step 1 (state machine): re-verify landing = working evidence.
                EndpointStateStore.markWorking(ctx, b.ip)
            } else {
                EndpointHistoryStore.recordStableResult(ctx, b.endpoint, null, null, alive = false)
            }
        }

        val ranked = ScanHelper.stableRanked(alive) {
            EndpointHistoryStore.goodCount(ctx, it)
        }.take(10)
        lastTop = ranked
        if (sourceIsTest) lastTopTest = ranked else lastTopBackup = ranked
        adapter.items = ranked

        val aliveSet = aliveByEp.keys
        val dead = backup.filter { it.endpoint !in aliveSet }
        lastDead = dead.map { it.endpoint }

        // v1.24: NO auto-remove — failed draws are evidence only
        // (APPEARS ⟹ NOT DEAD, user law 2026-10-26). Dead rows stay in
        // their store until the user deletes them explicitly (Backup
        // tab per-row ✕). History above still records the run's
        // events: truth accumulates across runs, it never deletes.
        val removed = 0

        rowHead.visibility = if (ranked.isEmpty()) View.GONE else View.VISIBLE
        tvEmpty.visibility = View.GONE
        lastRemoved = removed
        updateDeadSection(dead.size, removed)
        val bits = mutableListOf("✓ ${backup.size} re-verified", "${alive.size} alive", "${dead.size} dead")
        bits.add("just now")
        tvStatus.text = bits.joinToString(" · ")
        if (ranked.isEmpty()) {
            tvStatus.text = "No alive endpoint — ${dead.size} dead"
            Toast.makeText(ctx, "No alive endpoint", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(ctx, "✓ Stable top ${ranked.size}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Manual ms Refresh (v1.20, warm median): the user taps, reads warm
     * ms, and confirms (publishes) from this tab. v1.24: each row is
     * WARMED first (one landed handshake, up to 3 attempts, discarded),
     * then measured until 3 LANDED verify passes (fresh engine process
     * per pass = fresh flows), capped at 7 attempts per row. Warm-up
     * draws never enter the medians, so every measured value is warm
     * by construction. The shown value
     * is the MEDIAN of the 3 landed latencies (typical warm cost, not
     * the floor); with exactly 2 landed, the mean of the 2; with fewer
     * than 2, the old ms is kept. Failed passes count for nothing and
     * change nothing. Jitter comes from the median pass (mean of the 2
     * for the 2-landed case). Backup's stored ms updates with the shown
     * value (keeps min, as in finish()). No re-ranking, no history, no
     * dead handling — that stays with the re-verify run. Cancel reuses
     * the shared running/cancelled machinery (scan button becomes
     * ■ CANCEL); cancel discards all results.
     */
    private fun startRefresh() {
        val ctx = requireContext()
        if (running) {
            toast("Already running")
            return
        }
        if (lastTop.isEmpty()) {
            toast("Run a Stable scan first")
            return
        }
        if (!WgConfStore.exists(ctx)) {
            toast("Import your WARP .conf first")
            return
        }
        cancelled = false
        running = true
        btnScan.text = "■ CANCEL"
        btnRefresh.isEnabled = false
        progress.visibility = View.VISIBLE
        progress.max = lastTop.size
        progress.progress = 0
        tvStatus.text = "Refreshing ms 0/${lastTop.size}…"

        refreshOriginal = lastTop.map { ScanResult(it.ip, it.port, null, 0) }
        refreshLanded = mutableMapOf()
        // v1.24: warm-up phase first; measurement starts only for rows
        // that warmed (see runRefreshWarmPass).
        refreshWarmPass = 1
        refreshWarmed = mutableSetOf()
        runRefreshWarmPass(refreshOriginal)
    }

    /**
     * v1.24 warm-up phase: one fresh engine process (fresh flows) over
     * [targets] (rows not yet warmed). A row is warmed when it lands
     * at least one handshake; warm-up results are DISCARDED (never
     * added to [refreshLanded]). Rows still unwarm after
     * [WARMUP_MAX_ATTEMPTS] passes skip measurement and keep their old
     * ms — an unwarm row is unmeasured, never "dead". When warm-up ends,
     * measurement ([runRefreshPass]) starts over the warmed rows only.
     */
    private fun runRefreshWarmPass(targets: List<ScanResult>) {
        val confPath = WgConfStore.path(requireContext())
        val passTargets = targets
        val passFresh = java.util.Collections.synchronizedMap(mutableMapOf<String, ScanResult>())
        activity?.runOnUiThread {
            if (!cancelled) {
                progress.max = refreshOriginal.size
                progress.progress = refreshWarmed.size
                tvStatus.text = "Warming up · pass $refreshWarmPass/$WARMUP_MAX_ATTEMPTS · ${refreshWarmed.size}/${refreshOriginal.size} warmed…"
            }
        }
        engine.scan(
            ScanHelper.verifyArgs(passTargets, confPath),
            onResult = { r ->
                passFresh[r.endpoint] = r
                activity?.runOnUiThread {
                    if (cancelled) return@runOnUiThread
                    tvStatus.text = "Warming up · pass $refreshWarmPass/$WARMUP_MAX_ATTEMPTS · ${refreshWarmed.size + passFresh.size}/${refreshOriginal.size} warmed…"
                }
            },
            onProgress = {},
            onDone = { ok, err ->
                val act = activity ?: return@scan
                if (cancelled || !ok) {
                    act.runOnUiThread { finishRefreshMedian(act, ok, err) }
                    return@scan
                }
                refreshWarmed.addAll(passFresh.keys)
                val remaining = refreshOriginal.filter { it.endpoint !in refreshWarmed }
                act.runOnUiThread {
                    if (!cancelled) {
                        progress.max = refreshOriginal.size
                        progress.progress = refreshWarmed.size
                        tvStatus.text = "Warming up · pass $refreshWarmPass/$WARMUP_MAX_ATTEMPTS done · ${refreshWarmed.size}/${refreshOriginal.size} warmed"
                    }
                }
                if (cancelled) {
                    act.runOnUiThread { finishRefreshMedian(act, true, null) }
                } else if (remaining.isEmpty() || refreshWarmPass >= WARMUP_MAX_ATTEMPTS) {
                    // Measurement over warmed rows only; none warmed ->
                    // nothing to measure, finish (all rows keep old ms).
                    val warmed = refreshOriginal.filter { it.endpoint in refreshWarmed }
                    refreshPass = 1
                    refreshLanded = mutableMapOf()
                    if (warmed.isEmpty()) {
                        act.runOnUiThread { finishRefreshMedian(act, true, null) }
                    } else {
                        runRefreshPass(warmed)
                    }
                } else {
                    refreshWarmPass++
                    runRefreshWarmPass(remaining)
                }
            }
        )
    }

    /**
     * One measurement Refresh pass (v1.24: warm-up already ran — the
     * rows here are warm, and warm-up draws never enter the medians):
     * a fresh engine process (fresh flows) over [targets] (rows still
     * needing landed values). Landed results merge into [refreshLanded];
     * rows with 3 landed drop out of the next pass. Stops when every
     * row has 3 landed or 7 passes ran — then [finishRefreshMedian]
     * applies the medians (or keeps old ms).
     */
    private fun runRefreshPass(targets: List<ScanResult>) {
        val confPath = WgConfStore.path(requireContext())
        val passTargets = targets
        val passFresh = java.util.Collections.synchronizedMap(mutableMapOf<String, ScanResult>())
        activity?.runOnUiThread {
            if (!cancelled) {
                progress.max = passTargets.size
                progress.progress = 0
                tvStatus.text = "Refreshing ms · pass $refreshPass/$REFRESH_MAX_ATTEMPTS…"
            }
        }
        engine.scan(
            ScanHelper.verifyArgs(passTargets, confPath),
            onResult = { r ->
                passFresh[r.endpoint] = r
                activity?.runOnUiThread {
                    if (cancelled) return@runOnUiThread
                    progress.max = passTargets.size
                    progress.progress = passFresh.size
                    tvStatus.text = "Refreshing ms · pass $refreshPass/$REFRESH_MAX_ATTEMPTS · ${passFresh.size}/${passTargets.size}"
                }
            },
            onProgress = {},
            onDone = { ok, err ->
                val act = activity ?: return@scan
                if (cancelled || !ok) {
                    act.runOnUiThread { finishRefreshMedian(act, ok, err) }
                    return@scan
                }
                for ((ep, r) in passFresh.toMap()) {
                    refreshLanded.getOrPut(ep) { mutableListOf() }.add(r)
                }
                val remaining = refreshOriginal.filter {
                    (refreshLanded[it.endpoint]?.size ?: 0) < REFRESH_LANDED_NEEDED
                }
                val warmed = refreshOriginal.count {
                    (refreshLanded[it.endpoint]?.size ?: 0) >= REFRESH_LANDED_NEEDED
                }
                act.runOnUiThread {
                    if (!cancelled) {
                        progress.max = refreshOriginal.size
                        progress.progress = warmed
                        tvStatus.text = "Refreshing ms · pass $refreshPass/$REFRESH_MAX_ATTEMPTS done · $warmed/${refreshOriginal.size} warmed"
                    }
                }
                if (cancelled || remaining.isEmpty() || refreshPass >= REFRESH_MAX_ATTEMPTS) {
                    act.runOnUiThread { finishRefreshMedian(act, true, null) }
                } else {
                    refreshPass++
                    runRefreshPass(remaining)
                }
            }
        )
    }

    private fun finishRefreshMedian(
        act: android.app.Activity,
        ok: Boolean,
        err: String?
    ) {
        running = false
        btnScan.text = "Stable IP Scanner"
        btnRefresh.isEnabled = true
        progress.visibility = View.GONE
        val ctx = act.applicationContext
        if (cancelled) {
            tvStatus.text = "Stopped"
            return
        }
        if (!ok) {
            tvStatus.text = "Error: ${err ?: "unknown"}"
            Toast.makeText(ctx, "Error: ${err ?: "unknown"}", Toast.LENGTH_SHORT).show()
            return
        }
        val now = System.currentTimeMillis()
        var updated = 0
        lastTop = lastTop.map { row ->
            val landed = refreshLanded[row.endpoint] ?: emptyList()
            // Shown value: median of the first 3 landed (typical warm),
            // mean of 2 when exactly 2 landed, old ms otherwise. A null
            // median/mean ms (degenerate engine data) keeps the old row.
            val fresh: Pair<Long?, Long?>? = when {
                landed.size >= REFRESH_LANDED_NEEDED -> {
                    val sorted = landed.take(REFRESH_LANDED_NEEDED)
                        .sortedBy { it.latencyMs ?: Long.MAX_VALUE }
                    val median = sorted[REFRESH_LANDED_NEEDED / 2]
                    median.latencyMs?.let { it to median.jitterMs }
                }
                landed.size == 2 -> {
                    val msList = landed.mapNotNull { it.latencyMs }
                    if (msList.isEmpty()) null
                    else {
                        val jitList = landed.mapNotNull { it.jitterMs }
                        (msList.sum() / msList.size) to
                            (if (jitList.isEmpty()) null else jitList.sum() / jitList.size)
                    }
                }
                else -> null
            }
            if (fresh != null) {
                updated++
                // v1.22: refreshed ms writes back to the active source store.
                if (sourceIsTest) {
                    TestStore.add(ctx, BackupEntry(row.ip, row.port, fresh.first, fresh.second, now))
                } else {
                    BackupStore.add(ctx, BackupEntry(row.ip, row.port, fresh.first, fresh.second, now))
                }
                // Step 1 (state machine): refresh landing = working evidence.
                EndpointStateStore.markWorking(ctx, row.ip)
                row.copy(latencyMs = fresh.first, jitterMs = fresh.second)
            } else row
        }
        if (sourceIsTest) lastTopTest = lastTop else lastTopBackup = lastTop
        adapter.items = lastTop
        val unchanged = lastTop.size - updated
        tvStatus.text = "✓ ms refreshed — $updated updated" +
            (if (unchanged > 0) ", $unchanged unchanged" else "") + " · just now"
        Toast.makeText(ctx, "✓ ms refreshed", Toast.LENGTH_SHORT).show()
    }

    private fun updateDeadSection(deadCount: Int, removed: Int) {
        if (deadCount == 0 && removed == 0) {
            tvDead.visibility = View.GONE
            return
        }
        tvDead.visibility = View.VISIBLE
        val head = buildString {
            append("✕ Dead ($deadCount) — excluded from ranking")
            if (removed > 0) append(" · $removed removed from Backup (dead ×${EndpointHistoryStore.DEAD_STRIKES})")
        }
        tvDead.text = if (deadExpanded && lastDead.isNotEmpty()) {
            head + "\n" + lastDead.joinToString("\n")
        } else {
            "$head · tap to view"
        }
    }

    private fun toggleDead() {
        deadExpanded = !deadExpanded
        updateDeadSection(lastDead.size, lastRemoved)
    }

    /**
     * Compare mode (v1.26): BACKUP + TEST sections in one list, inside
     * this tab. Entry is blocked while a run is active (same guard as
     * the Source button) so a run's UI state never mixes with compare.
     * Single-source view state is restored on exit (rowHead/tvEmpty/
     * tvDead from lastTop/lastDead, never blindly VISIBLE).
     */
    private fun toggleCompare() {
        if (running) {
            toast("Stop the run first")
            return
        }
        if (!compareMode) {
            val ctx = requireContext()
            val backupEmpty = try { BackupStore.load(ctx).isEmpty() } catch (_: Exception) { true }
            val testEmpty = try { TestStore.load(ctx).isEmpty() } catch (_: Exception) { true }
            if (backupEmpty && testEmpty) {
                toast("Run a Stable scan first")
                return
            }
            compareMode = true
            btnCompare.text = "← BACK TO STABLE"
            btnScan.visibility = View.GONE
            rowHead.visibility = View.GONE
            rv.visibility = View.GONE
            tvEmpty.visibility = View.GONE
            tvDead.visibility = View.GONE
            layoutCompare.visibility = View.VISIBLE
            loadCompareData()
            updateComparePublishText()
        } else {
            compareMode = false
            btnCompare.text = "COMPARE 192 vs 195"
            layoutCompare.visibility = View.GONE
            btnScan.visibility = View.VISIBLE
            rv.visibility = View.VISIBLE
            rowHead.visibility = if (lastTop.isEmpty()) View.GONE else View.VISIBLE
            if (lastTop.isEmpty()) {
                tvEmpty.text = if (sourceIsTest) {
                    "Test list is empty — scan with TEST checked in Range"
                } else {
                    "Backup is empty — run a Scan first"
                }
                tvEmpty.visibility = View.VISIBLE
            } else {
                tvEmpty.visibility = View.GONE
            }
            updateDeadSection(lastDead.size, lastRemoved)
            adapter.items = lastTop
        }
    }

    /** Compare sections: top 10 per store, same ranking as Stable. */
    private fun loadCompareData() {
        val ctx = requireContext()
        val good = { ep: String ->
            try { EndpointHistoryStore.goodCount(ctx, ep) } catch (_: Exception) { 0 }
        }
        compareBackup = try {
            ScanHelper.stableRanked(
                BackupStore.load(ctx).map { ScanResult(it.ip, it.port, it.ms, 0, it.jitterMs) },
                good
            ).take(10)
        } catch (_: Exception) { emptyList() }
        compareTest = try {
            ScanHelper.stableRanked(
                TestStore.load(ctx).map { ScanResult(it.ip, it.port, it.ms, 0, it.jitterMs) },
                good
            ).take(10)
        } catch (_: Exception) { emptyList() }
        refreshCompareAdapter()
    }

    private fun refreshCompareAdapter() {
        val items = mutableListOf<CompareAdapter.Item>()
        items.add(CompareAdapter.Item.Header("BACKUP · 192.x"))
        if (compareBackup.isEmpty()) {
            items.add(CompareAdapter.Item.Empty("Backup is empty — run a Scan first"))
        } else {
            compareBackup.forEachIndexed { i, r ->
                items.add(CompareAdapter.Item.Row(r, i + 1, i == 0))
            }
        }
        items.add(CompareAdapter.Item.Header("TEST · 195.x"))
        if (compareTest.isEmpty()) {
            items.add(CompareAdapter.Item.Empty("Test list is empty — scan with TEST checked in Range"))
        } else {
            compareTest.forEachIndexed { i, r ->
                items.add(CompareAdapter.Item.Row(r, i + 1, i == 0))
            }
        }
        compareAdapter.items = items
    }

    private fun updateComparePublishText() {
        if (!::btnComparePublishSelected.isInitialized) return
        btnComparePublishSelected.text =
            if (selected.isEmpty()) "⧉ PUBLISH SELECTED"
            else "⧉ PUBLISH SELECTED (${selected.size})"
    }

    /**
     * Compare refresh (v1.26): one tap re-measures BOTH stores' warm
     * medians, BACKUP first then TEST, with the same warm-up + measure
     * method as single-source refresh. Sections are NOT re-ranked (ms
     * updates in place, like single refresh). A section that finished
     * keeps its fresh ms even if the user cancels the other section;
     * the cancelled section keeps old ms (never "dead"). Deliberately
     * mirrored, not refactored into single-source refresh, so the
     * working refresh path stays untouched.
     */
    private fun compareRefreshOrCancel() {
        if (running) {
            // A compare refresh is in progress: this button is its ■ CANCEL.
            cancelled = true
            engine.cancel()
            return
        }
        startCompareRefresh()
    }

    private fun startCompareRefresh() {
        val ctx = requireContext()
        if (compareBackup.isEmpty() && compareTest.isEmpty()) {
            toast("Run a Stable scan first")
            return
        }
        if (!WgConfStore.exists(ctx)) {
            toast("Import your WARP .conf first")
            return
        }
        cancelled = false
        running = true
        compareUpdated = 0
        compareUnchanged = 0
        btnCompareRefresh.text = "■ CANCEL"
        btnCompare.isEnabled = false
        btnComparePublishSelected.isEnabled = false
        progress.visibility = View.VISIBLE
        startComparePhase(0)
    }

    private fun startComparePhase(phase: Int) {
        comparePhase = phase
        val list = if (phase == 0) compareBackup else compareTest
        if (list.isEmpty()) {
            // Empty section: nothing to measure, advance or finish.
            if (phase == 0) startComparePhase(1) else finishCompareRefresh()
            return
        }
        compareRefreshOriginal = list.map { ScanResult(it.ip, it.port, null, 0) }
        compareRefreshLanded = mutableMapOf()
        compareWarmPass = 1
        compareWarmed = mutableSetOf()
        progress.max = compareRefreshOriginal.size
        progress.progress = 0
        tvStatus.text = "Refreshing ${if (phase == 0) "BACKUP" else "TEST"} ms 0/${compareRefreshOriginal.size}…"
        runCompareWarmPass(compareRefreshOriginal)
    }

    /** Warm-up for one compare phase: mirrors [runRefreshWarmPass]. */
    private fun runCompareWarmPass(targets: List<ScanResult>) {
        val confPath = WgConfStore.path(requireContext())
        val passFresh = java.util.Collections.synchronizedMap(mutableMapOf<String, ScanResult>())
        val phaseName = if (comparePhase == 0) "BACKUP" else "TEST"
        activity?.runOnUiThread {
            if (!cancelled) {
                progress.max = compareRefreshOriginal.size
                progress.progress = compareWarmed.size
                tvStatus.text = "Refreshing $phaseName · Warming up · pass $compareWarmPass/$WARMUP_MAX_ATTEMPTS · ${compareWarmed.size}/${compareRefreshOriginal.size} warmed…"
            }
        }
        engine.scan(
            ScanHelper.verifyArgs(targets, confPath),
            onResult = { r ->
                passFresh[r.endpoint] = r
                activity?.runOnUiThread {
                    if (cancelled) return@runOnUiThread
                    tvStatus.text = "Refreshing $phaseName · Warming up · pass $compareWarmPass/$WARMUP_MAX_ATTEMPTS · ${compareWarmed.size + passFresh.size}/${compareRefreshOriginal.size} warmed…"
                }
            },
            onProgress = {},
            onDone = { ok, err ->
                val act = activity ?: return@scan
                if (cancelled || !ok) {
                    act.runOnUiThread { finishCompareTerminal(ok, err) }
                    return@scan
                }
                compareWarmed.addAll(passFresh.keys)
                val remaining = compareRefreshOriginal.filter { it.endpoint !in compareWarmed }
                act.runOnUiThread {
                    if (!cancelled) {
                        progress.max = compareRefreshOriginal.size
                        progress.progress = compareWarmed.size
                        tvStatus.text = "Refreshing $phaseName · Warming up · pass $compareWarmPass/$WARMUP_MAX_ATTEMPTS done · ${compareWarmed.size}/${compareRefreshOriginal.size} warmed"
                    }
                }
                if (cancelled) {
                    act.runOnUiThread { finishCompareTerminal(true, null) }
                } else if (remaining.isEmpty() || compareWarmPass >= WARMUP_MAX_ATTEMPTS) {
                    val warmed = compareRefreshOriginal.filter { it.endpoint in compareWarmed }
                    comparePass = 1
                    compareRefreshLanded = mutableMapOf()
                    if (warmed.isEmpty()) {
                        // Nothing warmed: this section keeps old ms.
                        compareUnchanged += compareRefreshOriginal.size
                        act.runOnUiThread {
                            if (comparePhase == 0) startComparePhase(1) else finishCompareRefresh()
                        }
                    } else {
                        runCompareMeasurePass(warmed)
                    }
                } else {
                    compareWarmPass++
                    runCompareWarmPass(remaining)
                }
            }
        )
    }

    /** One measurement pass for one compare phase: mirrors [runRefreshPass]. */
    private fun runCompareMeasurePass(targets: List<ScanResult>) {
        val confPath = WgConfStore.path(requireContext())
        val passFresh = java.util.Collections.synchronizedMap(mutableMapOf<String, ScanResult>())
        val phaseName = if (comparePhase == 0) "BACKUP" else "TEST"
        activity?.runOnUiThread {
            if (!cancelled) {
                progress.max = targets.size
                progress.progress = 0
                tvStatus.text = "Refreshing $phaseName · pass $comparePass/$REFRESH_MAX_ATTEMPTS…"
            }
        }
        engine.scan(
            ScanHelper.verifyArgs(targets, confPath),
            onResult = { r ->
                passFresh[r.endpoint] = r
                activity?.runOnUiThread {
                    if (cancelled) return@runOnUiThread
                    progress.max = targets.size
                    progress.progress = passFresh.size
                    tvStatus.text = "Refreshing $phaseName · pass $comparePass/$REFRESH_MAX_ATTEMPTS · ${passFresh.size}/${targets.size}"
                }
            },
            onProgress = {},
            onDone = { ok, err ->
                val act = activity ?: return@scan
                if (cancelled || !ok) {
                    act.runOnUiThread { finishCompareTerminal(ok, err) }
                    return@scan
                }
                for ((ep, r) in passFresh.toMap()) {
                    compareRefreshLanded.getOrPut(ep) { mutableListOf() }.add(r)
                }
                val remaining = compareRefreshOriginal.filter {
                    (compareRefreshLanded[it.endpoint]?.size ?: 0) < REFRESH_LANDED_NEEDED
                }
                val warmed = compareRefreshOriginal.count {
                    (compareRefreshLanded[it.endpoint]?.size ?: 0) >= REFRESH_LANDED_NEEDED
                }
                act.runOnUiThread {
                    if (!cancelled) {
                        progress.max = compareRefreshOriginal.size
                        progress.progress = warmed
                        tvStatus.text = "Refreshing $phaseName · pass $comparePass/$REFRESH_MAX_ATTEMPTS done · $warmed/${compareRefreshOriginal.size} warmed"
                    }
                }
                if (cancelled || remaining.isEmpty() || comparePass >= REFRESH_MAX_ATTEMPTS) {
                    act.runOnUiThread { finishComparePhase() }
                } else {
                    comparePass++
                    runCompareMeasurePass(remaining)
                }
            }
        )
    }

    /** Applies one phase's medians (median of 3 / mean of 2 / keep old),
     * writes them to that phase's store, then advances or finalizes. */
    private fun finishComparePhase() {
        val ctx = requireContext().applicationContext
        val now = System.currentTimeMillis()
        val phaseList = if (comparePhase == 0) compareBackup else compareTest
        val updatedList = phaseList.map { row ->
            val landed = compareRefreshLanded[row.endpoint] ?: emptyList()
            val fresh: Pair<Long?, Long?>? = when {
                landed.size >= REFRESH_LANDED_NEEDED -> {
                    val sorted = landed.take(REFRESH_LANDED_NEEDED)
                        .sortedBy { it.latencyMs ?: Long.MAX_VALUE }
                    val median = sorted[REFRESH_LANDED_NEEDED / 2]
                    median.latencyMs?.let { it to median.jitterMs }
                }
                landed.size == 2 -> {
                    val msList = landed.mapNotNull { it.latencyMs }
                    if (msList.isEmpty()) null
                    else {
                        val jitList = landed.mapNotNull { it.jitterMs }
                        (msList.sum() / msList.size) to
                            (if (jitList.isEmpty()) null else jitList.sum() / jitList.size)
                    }
                }
                else -> null
            }
            if (fresh != null) {
                compareUpdated++
                if (comparePhase == 0) {
                    BackupStore.add(ctx, BackupEntry(row.ip, row.port, fresh.first, fresh.second, now))
                } else {
                    TestStore.add(ctx, BackupEntry(row.ip, row.port, fresh.first, fresh.second, now))
                }
                EndpointStateStore.markWorking(ctx, row.ip)
                row.copy(latencyMs = fresh.first, jitterMs = fresh.second)
            } else {
                compareUnchanged++
                row
            }
        }
        if (comparePhase == 0) compareBackup = updatedList else compareTest = updatedList
        refreshCompareAdapter()
        if (comparePhase == 0) {
            startComparePhase(1)
        } else {
            finishCompareRefresh()
        }
    }

    private fun finishCompareRefresh() {
        running = false
        btnCompareRefresh.text = "↻ REFRESH"
        btnCompareRefresh.isEnabled = true
        btnCompare.isEnabled = true
        btnComparePublishSelected.isEnabled = true
        progress.visibility = View.GONE
        tvStatus.text = "✓ ms refreshed — $compareUpdated updated" +
            (if (compareUnchanged > 0) ", $compareUnchanged unchanged" else "") + " · just now"
        Toast.makeText(requireContext(), "✓ ms refreshed", Toast.LENGTH_SHORT).show()
    }

    private fun finishCompareTerminal(ok: Boolean, err: String?) {
        running = false
        btnCompareRefresh.text = "↻ REFRESH"
        btnCompareRefresh.isEnabled = true
        btnCompare.isEnabled = true
        btnComparePublishSelected.isEnabled = true
        progress.visibility = View.GONE
        if (cancelled) {
            tvStatus.text = "Stopped"
            return
        }
        if (!ok) {
            tvStatus.text = "Error: ${err ?: "unknown"}"
            Toast.makeText(requireContext(), "Error: ${err ?: "unknown"}", Toast.LENGTH_SHORT).show()
            return
        }
    }

    /**
     * Compare publish-selected: mirrors [publishSelected] but over the
     * compare lists, so the live-feed publish path itself stays
     * untouched. Same v1.27 shape: confirm only, no JSON preview.
     */
    private fun comparePublishSelected() {
        val pool = (compareBackup + compareTest).distinctBy { it.endpoint }
        val picked = pool.filter { it.endpoint in selected }
        if (picked.isEmpty()) {
            toast("Tick endpoints first (checkbox on each row)")
            return
        }
        val rankedSel = picked.sortedWith(
            compareBy(
                { ScanHelper.rangeTier(it.ip) },
                { (it.latencyMs ?: Long.MAX_VALUE) + (it.jitterMs ?: 0L) },
                { it.ip }
            )
        ).take(10)
        val json = buildPublishJson(rankedSel)
        showPublishConfirm { comparePushSelectedJson(json) }
    }

    /** Push for compare publish-selected; mirrors [pushSelectedJson]. */
    private fun comparePushSelectedJson(json: String) {
        val token = try {
            TokenStore.getToken(requireContext())
        } catch (_: Exception) {
            null
        }
        if (token == null) {
            showCopyDialog(json, "Publish selected")
            return
        }
        btnComparePublishSelected.isEnabled = false
        btnComparePublishSelected.text = "Publishing…"
        Thread {
            val result = GitHubPush.push(token, json)
            activity?.runOnUiThread {
                try {
                    btnComparePublishSelected.isEnabled = true
                } catch (_: Exception) { /* view gone */ }
                updateComparePublishText()
                when (result) {
                    is GitHubPush.Result.Ok -> {
                        selected.clear()
                        refreshCompareAdapter()
                        updateComparePublishText()
                        updatePublishSelectedText()
                        toast("Published to GitHub ✅")
                    }
                    is GitHubPush.Result.Err -> {
                        toast("Push failed: ${result.reason}")
                        try {
                            showCopyDialog(json, "Publish selected")
                        } catch (_: Exception) { /* view gone */ }
                    }
                }
            }
        }.start()
    }

    /**
     * "Explore this winner's /24": deep-link into the Range tab with the
     * endpoint's /24 + port pre-filled, auto-starting the neighbor scan.
     */
    private fun exploreRange(r: ScanResult) {
        val vm = androidx.lifecycle.ViewModelProvider(requireActivity())[ScanViewModel::class.java]
        vm.pendingExploreCidr = ScanHelper.cidr24(r.ip)
        vm.pendingExplorePort = r.port.toString()
        try {
            requireActivity()
                .findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottomNav)
                .selectedItemId = R.id.nav_range
        } catch (_: Exception) {
            toast("Could not open Range tab")
        }
    }

    private fun copyAll() {
        if (lastTop.isEmpty()) {
            toast("Nothing to copy")
            return
        }
        val text = lastTop.joinToString("\n") { it.endpoint }
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("stable_endpoints", text))
        btnCopyAll.text = "✓ COPIED"
        toast("${lastTop.size} endpoints copy ကူးပြီးပြီ")
        Handler(Looper.getMainLooper()).postDelayed({ btnCopyAll.text = "⧉ COPY ALL" }, 1500)
    }

    /**
     * Publish the currently displayed top-10 as a JSON document for the
     * companion VPN app. When a GitHub token is saved in Settings the JSON
     * is pushed directly to endpoints.json on GitHub; otherwise it falls
     * back to the v1.9 manual flow (scrollable preview + copy to clipboard).
     */
    private fun publishTop10() {
        // Defense in depth: the button is hidden on the Test view, but a
        // Test list must never reach the feed through this path either.
        if (sourceIsTest) {
            toast("Test list can't be published — promote or use Publish selected")
            return
        }
        if (lastTop.isEmpty()) {
            toast("Run a Stable scan first")
            return
        }
        val json = buildPublishJson(lastTop.take(10))
        val token = try {
            TokenStore.getToken(requireContext())
        } catch (_: Exception) {
            null
        }
        if (token == null) {
            showCopyDialog(json)
            return
        }
        // Auto-push off the main thread; the token never leaves this scope
        // except inside the Authorization header, and is never logged.
        btnPublish.isEnabled = false
        btnPublish.text = "Publishing…"
        Thread {
            val result = GitHubPush.push(token, json)
            activity?.runOnUiThread {
                try {
                    btnPublish.isEnabled = true
                    btnPublish.text = "⧉ PUBLISH"
                } catch (_: Exception) { /* view gone */ }
                when (result) {
                    is GitHubPush.Result.Ok -> toast("Published to GitHub ✅")
                    is GitHubPush.Result.Err -> {
                        toast("Push failed: ${result.reason}")
                        try {
                            showCopyDialog(json)
                        } catch (_: Exception) { /* view gone */ }
                    }
                }
            }
        }.start()
    }

    /** Manual fallback: scrollable monospace preview, then copy to clipboard. */
    private fun showCopyDialog(json: String, title: String = "Publish top ${lastTop.size}") {
        val tv = TextView(requireContext()).apply {
            text = json
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setPadding(32, 20, 32, 20)
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(requireContext()).apply { addView(tv) }
        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setView(scroll)
            .setPositiveButton("Copy JSON") { _, _ ->
                val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("stable_top10", json))
                toast("Copied — paste into endpoints.json on GitHub")
            }
            .setNegativeButton("Close", null)
            .show()
    }

    /**
     * Publish-selected (v1.22): the override path. Ticks from either view
     * (selection survives source switches) form the feed — exactly the
     * picked endpoints, in displayed rank order (range tier, then
     * ms+jitter), capped at 10 like the normal feed. v1.27: NO JSON
     * preview (user 2026-10-27: nothing to view there) — the Yes/No
     * confirm below is the only step before the push. The push still
     * replaces endpoints.json wholesale.
     */
    private fun publishSelected() {
        val pool = (lastTopBackup + lastTopTest).distinctBy { it.endpoint }
        val picked = pool.filter { it.endpoint in selected }
        if (picked.isEmpty()) {
            toast("Tick endpoints first (checkbox on each row)")
            return
        }
        val rankedSel = picked.sortedWith(
            compareBy(
                { ScanHelper.rangeTier(it.ip) },
                { (it.latencyMs ?: Long.MAX_VALUE) + (it.jitterMs ?: 0L) },
                { it.ip }
            )
        ).take(10)
        val json = buildPublishJson(rankedSel)
        showPublishConfirm { pushSelectedJson(json) }
    }

    /**
     * The only step before a publish-selected push (user 2026-10-27):
     * ask explicitly, in English. Yes pushes; No just closes this
     * dialog (back to the list).
     */
    private fun showPublishConfirm(onYes: () -> Unit) {
        AlertDialog.Builder(requireContext())
            .setMessage("Are you sure you want to publish?")
            .setPositiveButton("Yes") { _, _ -> onYes() }
            .setNegativeButton("No", null)
            .show()
    }

    /** Push for Publish-selected; mirrors publishTop10's push handling. */
    private fun pushSelectedJson(json: String) {
        val token = try {
            TokenStore.getToken(requireContext())
        } catch (_: Exception) {
            null
        }
        if (token == null) {
            showCopyDialog(json, "Publish selected")
            return
        }
        btnPublishSelected.isEnabled = false
        btnPublishSelected.text = "Publishing…"
        Thread {
            val result = GitHubPush.push(token, json)
            activity?.runOnUiThread {
                try {
                    btnPublishSelected.isEnabled = true
                } catch (_: Exception) { /* view gone */ }
                updatePublishSelectedText()
                when (result) {
                    is GitHubPush.Result.Ok -> {
                        selected.clear()
                        adapter.items = lastTop
                        updatePublishSelectedText()
                        toast("Published to GitHub ✅")
                    }
                    is GitHubPush.Result.Err -> {
                        toast("Push failed: ${result.reason}")
                        try {
                            showCopyDialog(json, "Publish selected")
                        } catch (_: Exception) { /* view gone */ }
                    }
                }
            }
        }.start()
    }

    /**
     * Settings dialog: GitHub token (PAT) for auto-push. The token value
     * is never displayed after entry — only set/unset state is shown.
     */
    private fun showSettingsDialog() {
        val ctx = requireContext()
        val isSet = try {
            TokenStore.hasToken(ctx)
        } catch (_: Exception) {
            false
        }
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
        }
        val tvState = TextView(ctx).apply {
            text = "GitHub token: " + if (isSet) "set ✓" else "not set"
            textSize = 14f
            setPadding(0, 0, 0, 16)
        }
        val et = EditText(ctx).apply {
            hint = "Paste PAT here"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val tvHint = TextView(ctx).apply {
            text = "Fine-grained PAT, Warp-IP-Scanner repo only, Contents read/write.\n" +
                "Create at github.com/settings/tokens"
            textSize = 12f
            setTextColor(resources.getColor(R.color.gray, null))
            setPadding(0, 16, 0, 0)
        }
        layout.addView(tvState)
        layout.addView(et)
        layout.addView(tvHint)
        AlertDialog.Builder(ctx)
            .setTitle("Settings")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val t = et.text.toString().trim()
                if (t.isEmpty()) {
                    toast("Token is empty")
                } else {
                    TokenStore.setToken(ctx, t)
                    toast("Token saved")
                }
            }
            .setNeutralButton("Clear") { _, _ ->
                TokenStore.clearToken(ctx)
                toast("Token cleared")
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun buildPublishJson(entries: List<ScanResult>): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        val eps = JSONArray()
        for (r in entries) {
            eps.put(JSONObject().apply {
                put("ip", r.ip)
                put("port", r.port)
                put("ms", r.latencyMs ?: 0L)
                if (r.jitterMs != null) put("jitter_ms", r.jitterMs) else put("jitter_ms", JSONObject.NULL)
            })
        }
        return JSONObject().apply {
            put("v", 1)
            put("updated_at", sdf.format(Date()))
            put("isp", "MPT")
            put("endpoints", eps)
        }.toString(2)
    }
}
