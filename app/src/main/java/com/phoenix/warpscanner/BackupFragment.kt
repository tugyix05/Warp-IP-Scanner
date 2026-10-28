package com.phoenix.warpscanner

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File

/**
 * Persistent backup list of known-good endpoints: copy, export CSV, clear.
 * Plus the v1.22 separate test list: per-row delete, promote to Backup,
 * and delete-all-tests (tests only — Backup is never touched by it).
 *
 * v1.23: the Test section gains ↻ Refresh ms (warm-median re-measure
 * over the Test rows, writing back to TestStore only — same rules as
 * Stable's Refresh: median of 3 landed, mean of 2, old ms otherwise)
 * and → Stable (opens the Stable tab on its Test view).
 */
class BackupFragment : Fragment() {

    private lateinit var adapter: BackupAdapter
    private var entries: MutableList<BackupEntry> = mutableListOf()
    private lateinit var testAdapter: TestAdapter
    private var testEntries: MutableList<BackupEntry> = mutableListOf()

    // Warm-median Test refresh (v1.23): one fresh engine pass per round
    // over the rows still needing landed values; [testRefreshLanded]
    // collects each row's landed pass results in pass order.
    private lateinit var testEngine: ScanEngine
    private lateinit var btnTestRefresh: TextView
    @Volatile private var testRefreshCancelled = false
    private var testRefreshRunning = false
    private var testRefreshPass = 0
    private var testRefreshOriginal: List<BackupEntry> = emptyList()
    private var testRefreshLanded: MutableMap<String, MutableList<ScanResult>> = mutableMapOf()
    // v1.24 warm-up state: rows that landed at least one handshake.
    private var testRefreshWarmPass = 0
    private var testRefreshWarmed: MutableSet<String> = mutableSetOf()

    companion object {
        private const val TEST_REFRESH_LANDED_NEEDED = 3
        private const val TEST_REFRESH_MAX_ATTEMPTS = 7
        // v1.24 warm-up: attempts to land ONE handshake per row before
        // measuring. Warm-up draws are discarded — they never enter
        // the medians, so every measured value is warm by construction.
        private const val TEST_WARMUP_MAX_ATTEMPTS = 3
    }

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) exportLegacy() else toast("Storage permission denied")
        }

    override fun onCreateView(inf: LayoutInflater, c: ViewGroup?, s: Bundle?): View? =
        inf.inflate(R.layout.fragment_backup, c, false)

    override fun onViewCreated(v: View, s: Bundle?) {
        testEngine = ScanEngine(requireContext())
        val rv = v.findViewById<RecyclerView>(R.id.rvBackup)
        rv.layoutManager = LinearLayoutManager(requireContext())
        adapter = BackupAdapter(
            onCopy = { e ->
                val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("endpoint", e.endpoint))
                toast(e.endpoint + " copied")
            },
            onDelete = { e ->
                BackupStore.remove(requireContext(), e.ip, e.port)
                refresh()
            }
        )
        rv.adapter = adapter

        v.findViewById<TextView>(R.id.btnExport).setOnClickListener { export() }
        v.findViewById<TextView>(R.id.btnClear).setOnClickListener {
            BackupStore.clear(requireContext())
            refresh()
            toast("Backup cleared")
        }

        val rvTest = v.findViewById<RecyclerView>(R.id.rvTest)
        rvTest.layoutManager = LinearLayoutManager(requireContext())
        testAdapter = TestAdapter(
            onCopy = { e ->
                val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("endpoint", e.endpoint))
                toast(e.endpoint + " copied")
            },
            onDelete = { e ->
                TestStore.remove(requireContext(), e.ip, e.port)
                refresh()
            },
            onPromote = { e ->
                TestStore.remove(requireContext(), e.ip, e.port)
                BackupStore.add(requireContext(), e)
                refresh()
                toast(e.endpoint + " promoted to Backup")
            }
        )
        rvTest.adapter = testAdapter

        btnTestRefresh = v.findViewById(R.id.btnTestRefresh)
        btnTestRefresh.setOnClickListener {
            if (testRefreshRunning) {
                testRefreshCancelled = true
                testEngine.cancel()
                testRefreshRunning = false
                btnTestRefresh.text = "↻ Refresh ms"
                toast("Stopped")
            } else {
                startTestRefresh()
            }
        }
        v.findViewById<TextView>(R.id.btnTestStable).setOnClickListener {
            val vm = ViewModelProvider(requireActivity())[ScanViewModel::class.java]
            vm.pendingStableSourceIsTest = true
            try {
                requireActivity()
                    .findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottomNav)
                    .selectedItemId = R.id.nav_stable
            } catch (_: Exception) {
                toast("Could not open Stable tab")
            }
        }

        v.findViewById<TextView>(R.id.btnClearTests).setOnClickListener {
            val n = testEntries.size
            if (n == 0) {
                toast("No tests to delete")
                return@setOnClickListener
            }
            AlertDialog.Builder(requireContext())
                .setTitle("Delete all tests?")
                .setMessage("Delete $n test endpoints? Backup is not touched.")
                .setPositiveButton("Delete") { _, _ ->
                    TestStore.clear(requireContext())
                    refresh()
                    toast("Tests cleared")
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    /**
     * Tabs are shown/hidden, not recreated (v1.23) — onResume does not
     * fire on a tab switch, so refresh here: a Range scan may have saved
     * new Test rows while this tab was hidden.
     */
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && ::adapter.isInitialized && ::testAdapter.isInitialized) refresh()
    }

    override fun onDestroyView() {
        try { testEngine.cancel() } catch (_: Exception) {}
        super.onDestroyView()
    }

    /**
     * Warm-median Test refresh (v1.23): same rules as Stable's Refresh —
     * v1.24: each Test row is WARMED first (one landed handshake, up
     * to 3 attempts, discarded), then measured until 3 LANDED verify
     * passes (fresh engine process per pass = fresh flows), capped at
     * 7 attempts per row. Shown value = MEDIAN of the 3 landed
     * latencies; with exactly 2 landed, the mean of the 2; with fewer
     * than 2, the old ms is kept. Writes back to TestStore only — no
     * history, no publish. Refresh is a measurement, not a verify: it
     * does not use the 4-draw standard.
     */
    private fun startTestRefresh() {
        val ctx = requireContext()
        if (testEntries.isEmpty()) {
            toast("No tests to refresh")
            return
        }
        if (!WgConfStore.exists(ctx)) {
            toast("Import your WARP .conf first")
            return
        }
        testRefreshCancelled = false
        testRefreshRunning = true
        btnTestRefresh.text = "■ Stop"
        testRefreshOriginal = testEntries.toList()
        testRefreshLanded = mutableMapOf()
        // v1.24: warm-up phase first; measurement starts only for rows
        // that warmed (see runTestRefreshWarmPass).
        testRefreshWarmPass = 1
        testRefreshWarmed = mutableSetOf()
        runTestRefreshWarmPass(testRefreshOriginal)
    }

    /**
     * v1.24 warm-up phase: one fresh engine process (fresh flows) over
     * [targets] (rows not yet warmed). A row is warmed when it lands
     * at least one handshake; warm-up results are DISCARDED (never
     * added to [testRefreshLanded]). Rows still unwarm after
     * [TEST_WARMUP_MAX_ATTEMPTS] passes skip measurement and keep
     * their old ms — an unwarm row is unmeasured, never "dead".
     */
    private fun runTestRefreshWarmPass(targets: List<BackupEntry>) {
        val confPath = WgConfStore.path(requireContext())
        val passTargets = targets.map { ScanResult(it.ip, it.port, null, 0) }
        val passFresh = java.util.Collections.synchronizedMap(mutableMapOf<String, ScanResult>())
        activity?.runOnUiThread {
            if (!testRefreshCancelled) {
                btnTestRefresh.text = "↻ warming pass $testRefreshWarmPass/$TEST_WARMUP_MAX_ATTEMPTS…"
            }
        }
        testEngine.scan(
            ScanHelper.verifyArgs(passTargets, confPath),
            onResult = { r ->
                passFresh[r.endpoint] = r
                activity?.runOnUiThread {
                    if (!testRefreshCancelled) {
                        btnTestRefresh.text =
                            "↻ warming pass $testRefreshWarmPass/$TEST_WARMUP_MAX_ATTEMPTS · ${testRefreshWarmed.size + passFresh.size}/${testRefreshOriginal.size}"
                    }
                }
            },
            onProgress = {},
            onDone = { ok, err ->
                val act = activity ?: return@scan
                act.runOnUiThread {
                    if (testRefreshCancelled) {
                        testRefreshRunning = false
                        btnTestRefresh.text = "↻ Refresh ms"
                        return@runOnUiThread
                    }
                    if (!ok) {
                        testRefreshRunning = false
                        btnTestRefresh.text = "↻ Refresh ms"
                        Toast.makeText(act.applicationContext, "Error: ${err ?: "unknown"}", Toast.LENGTH_SHORT).show()
                        return@runOnUiThread
                    }
                    testRefreshWarmed.addAll(passFresh.keys)
                    val remaining = testRefreshOriginal.filter { it.endpoint !in testRefreshWarmed }
                    if (remaining.isEmpty() || testRefreshWarmPass >= TEST_WARMUP_MAX_ATTEMPTS) {
                        // Measurement over warmed rows only; none warmed ->
                        // finish (all rows keep old ms).
                        val warmed = testRefreshOriginal.filter { it.endpoint in testRefreshWarmed }
                        testRefreshPass = 1
                        testRefreshLanded = mutableMapOf()
                        if (warmed.isEmpty()) {
                            finishTestRefresh(act)
                        } else {
                            runTestRefreshPass(warmed)
                        }
                    } else {
                        testRefreshWarmPass++
                        runTestRefreshWarmPass(remaining)
                    }
                }
            }
        )
    }

    /** One Test-refresh measurement pass over [targets] (v1.24: rows here are warm already). */
    private fun runTestRefreshPass(targets: List<BackupEntry>) {
        val confPath = WgConfStore.path(requireContext())
        val passTargets = targets.map { ScanResult(it.ip, it.port, null, 0) }
        val passFresh = java.util.Collections.synchronizedMap(mutableMapOf<String, ScanResult>())
        activity?.runOnUiThread {
            if (!testRefreshCancelled) {
                btnTestRefresh.text = "↻ pass $testRefreshPass/$TEST_REFRESH_MAX_ATTEMPTS…"
            }
        }
        testEngine.scan(
            ScanHelper.verifyArgs(passTargets, confPath),
            onResult = { r ->
                passFresh[r.endpoint] = r
                activity?.runOnUiThread {
                    if (!testRefreshCancelled) {
                        btnTestRefresh.text =
                            "↻ pass $testRefreshPass/$TEST_REFRESH_MAX_ATTEMPTS · ${passFresh.size}/${passTargets.size}"
                    }
                }
            },
            onProgress = {},
            onDone = { ok, err ->
                val act = activity ?: return@scan
                act.runOnUiThread {
                    if (testRefreshCancelled) {
                        testRefreshRunning = false
                        btnTestRefresh.text = "↻ Refresh ms"
                        return@runOnUiThread
                    }
                    if (!ok) {
                        testRefreshRunning = false
                        btnTestRefresh.text = "↻ Refresh ms"
                        Toast.makeText(act.applicationContext, "Error: ${err ?: "unknown"}", Toast.LENGTH_SHORT).show()
                        return@runOnUiThread
                    }
                    for ((ep, r) in passFresh.toMap()) {
                        testRefreshLanded.getOrPut(ep) { mutableListOf() }.add(r)
                    }
                    val remaining = testRefreshOriginal.filter {
                        (testRefreshLanded[it.endpoint]?.size ?: 0) < TEST_REFRESH_LANDED_NEEDED
                    }
                    if (remaining.isEmpty() || testRefreshPass >= TEST_REFRESH_MAX_ATTEMPTS) {
                        finishTestRefresh(act)
                    } else {
                        testRefreshPass++
                        runTestRefreshPass(remaining)
                    }
                }
            }
        )
    }

    private fun finishTestRefresh(act: android.app.Activity) {
        testRefreshRunning = false
        btnTestRefresh.text = "↻ Refresh ms"
        if (testRefreshCancelled) return
        val ctx = act.applicationContext
        val now = System.currentTimeMillis()
        var updated = 0
        for (row in testRefreshOriginal) {
            val landed = testRefreshLanded[row.endpoint] ?: emptyList()
            val fresh: Pair<Long?, Long?>? = when {
                landed.size >= TEST_REFRESH_LANDED_NEEDED -> {
                    val sorted = landed.take(TEST_REFRESH_LANDED_NEEDED)
                        .sortedBy { it.latencyMs ?: Long.MAX_VALUE }
                    val median = sorted[TEST_REFRESH_LANDED_NEEDED / 2]
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
                TestStore.add(ctx, BackupEntry(row.ip, row.port, fresh.first, fresh.second, now))
                // Step 1 (state machine): refresh landing = working evidence.
                EndpointStateStore.markWorking(ctx, row.ip)
            }
        }
        // Stores above use the application context (safe even if the
        // fragment detached mid-run); the list UI needs an attached view.
        if (isAdded) refresh()
        val unchanged = testRefreshOriginal.size - updated
        Toast.makeText(ctx, "✓ Test ms refreshed — $updated updated" +
            (if (unchanged > 0) ", $unchanged unchanged" else ""), Toast.LENGTH_SHORT).show()
    }

    private fun refresh() {
        entries = BackupStore.load(requireContext())
        adapter.items = entries
        view?.findViewById<TextView>(R.id.tvBackupCount)?.text = "Saved (${entries.size})"
        testEntries = TestStore.load(requireContext())
        testAdapter.items = testEntries
        view?.findViewById<TextView>(R.id.tvTestCount)?.text = "Test (${testEntries.size})"
    }

    private fun buildCsv(): String {
        val sb = StringBuilder("ip,port,latency_ms,jitter_ms,saved_at\n")
        for (e in entries) {
            sb.append(e.ip).append(',')
                .append(e.port).append(',')
                .append(e.ms ?: "").append(',')
                .append(e.jitterMs ?: "").append(',')
                .append(e.savedAt).append('\n')
        }
        return sb.toString()
    }

    /** Save the CSV straight into the Download folder (no share sheet). */
    private fun export() {
        if (entries.isEmpty()) {
            toast("Nothing to export")
            return
        }
        if (Build.VERSION.SDK_INT >= 29) {
            exportMediaStore()
        } else {
            if (ContextCompat.checkSelfPermission(
                    requireContext(), Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                exportLegacy()
            } else {
                permLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
    }

    private fun exportMediaStore() {
        val name = "warp-endpoints-${System.currentTimeMillis()}.csv"
        try {
            val resolver = requireContext().contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("MediaStore insert failed")
            resolver.openOutputStream(uri)?.use { it.write(buildCsv().toByteArray()) }
                ?: throw IllegalStateException("cannot open output")
            toast("Saved to Download/$name")
        } catch (e: Exception) {
            toast("Export error: ${e.message}")
        }
    }

    private fun exportLegacy() {
        val name = "warp-endpoints-${System.currentTimeMillis()}.csv"
        try {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!dir.exists()) dir.mkdirs()
            File(dir, name).writeText(buildCsv())
            toast("Saved to Download/$name")
        } catch (e: Exception) {
            toast("Export error: ${e.message}")
        }
    }

    private fun toast(m: String) =
        Toast.makeText(requireContext(), m, Toast.LENGTH_SHORT).show()

    class BackupAdapter(
        private val onCopy: (BackupEntry) -> Unit,
        private val onDelete: (BackupEntry) -> Unit
    ) : RecyclerView.Adapter<BackupAdapter.VH>() {

        var items: List<BackupEntry> = emptyList()
            set(v) { field = v; notifyDataSetChanged() }

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val ip: TextView = v.findViewById(R.id.tvIp)
            val ms: TextView = v.findViewById(R.id.tvMs)
            val del: TextView = v.findViewById(R.id.btnDelete)
        }

        override fun onCreateViewHolder(p: ViewGroup, vt: Int): VH =
            VH(LayoutInflater.from(p.context).inflate(R.layout.item_backup, p, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(h: VH, pos: Int) {
            val e = items[pos]
            h.ip.text = e.endpoint
            h.ms.text = if (e.ms != null) "${e.ms} ms" else ""
            h.itemView.setOnClickListener { onCopy(e) }
            h.del.setOnClickListener { onDelete(e) }
        }
    }

    class TestAdapter(
        private val onCopy: (BackupEntry) -> Unit,
        private val onDelete: (BackupEntry) -> Unit,
        private val onPromote: (BackupEntry) -> Unit
    ) : RecyclerView.Adapter<TestAdapter.VH>() {

        var items: List<BackupEntry> = emptyList()
            set(v) { field = v; notifyDataSetChanged() }

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val ip: TextView = v.findViewById(R.id.tvIp)
            val ms: TextView = v.findViewById(R.id.tvMs)
            val promote: Button = v.findViewById(R.id.btnPromote)
            val del: Button = v.findViewById(R.id.btnDelete)
        }

        override fun onCreateViewHolder(p: ViewGroup, vt: Int): VH =
            VH(LayoutInflater.from(p.context).inflate(R.layout.item_test, p, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(h: VH, pos: Int) {
            val e = items[pos]
            h.ip.text = e.endpoint
            h.ms.text = if (e.ms != null) "${e.ms} ms" else ""
            h.itemView.setOnClickListener { onCopy(e) }
            h.promote.setOnClickListener { onPromote(e) }
            h.del.setOnClickListener { onDelete(e) }
        }
    }
}
