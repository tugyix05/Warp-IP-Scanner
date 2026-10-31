package com.phoenix.warpscanner

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Range scan: user-supplied CIDRs -> stable endpoints appended to the
 * persistent backup list (the "backup list" feature), or to the separate
 * test list when the TEST box is checked (v1.22: test entries never reach
 * Stable or Publish; the TEST box governs every save from this tab,
 * including deep-link explores).
 *
 * v1.23, 4-draw standard: a range run is FOUR fresh engine passes
 * (fresh flows each pass) over the same CIDRs, and an endpoint is saved
 * if it is stable (0% loss) in ANY of the 4 passes — the same union
 * rule as Scan/Stable. Four passes widen discovery (more draws for
 * each endpoint to prove itself); they do not narrow it.
 * v1.30: a stable endpoint shows as a row AND saves the moment it
 * lands (no waiting for pass 4), and the run stops as soon as 10
 * distinct stable endpoints are collected. The TEST destination is
 * frozen at run start.
 */
class RangeFragment : Fragment() {

    private lateinit var engine: ScanEngine
    private lateinit var btnScan: Button
    private lateinit var tvProgress: TextView
    private lateinit var etCidrs: EditText
    private lateinit var etPorts: EditText
    private lateinit var cbTest: CheckBox
    private lateinit var vm: ScanViewModel
    private var added = 0

    /** 4-pass run state (v1.23). v1.30: the union across passes is the
     *  live state — rows show and save as they land, and the run stops
     *  as soon as 10 distinct stable endpoints are collected. */
    private var rangePass = 0
    private val rangeUnion = LinkedHashMap<String, ScanResult>()
    private lateinit var rvRangeStable: RecyclerView
    private val rangeAdapter = RangeStableAdapter()
    private var rangeStopped = false
    private var rangeDestTest = false
    private var rangeCidrs = ""
    private var rangePorts = "500"

    companion object {
        private const val RANGE_PASSES = 4
        /** v1.30: stop as soon as this many distinct stable endpoints
         *  are collected — in one pass, or accumulated across passes. */
        private const val RANGE_STOP_AT = 10
    }

    /** True while the CIDR field is being set programmatically (not by the user). */
    private var programmaticCidr = false

    override fun onCreateView(inf: LayoutInflater, c: ViewGroup?, s: Bundle?): View? =
        inf.inflate(R.layout.fragment_range, c, false)

    override fun onViewCreated(v: View, s: Bundle?) {
        engine = ScanEngine(requireContext())
        vm = ViewModelProvider(requireActivity())[ScanViewModel::class.java]
        etCidrs = v.findViewById(R.id.etCidrs)
        etPorts = v.findViewById(R.id.etPorts)
        cbTest = v.findViewById(R.id.cbTest)
        btnScan = v.findViewById(R.id.btnRangeScan)
        tvProgress = v.findViewById(R.id.tvRangeProgress)
        rvRangeStable = v.findViewById(R.id.rvRangeStable)
        rvRangeStable.layoutManager = LinearLayoutManager(requireContext())
        rvRangeStable.adapter = rangeAdapter
        rvRangeStable.isNestedScrollingEnabled = false

        refreshCidrField()
        etPorts.setText("500")

        consumeExplore()

        // Manual edits take ownership of the field: auto-fill stops touching it.
        etCidrs.doAfterTextChanged {
            if (!programmaticCidr) vm.rangeCidrsManual = true
        }

        btnScan.setOnClickListener {
            if (engine.running) {
                rangeStopped = true
                engine.cancel()
                btnScan.text = "Scan range"
                // v1.30: Stop keeps what is already stable — rows are
                // saved as they land, so nothing found so far is lost.
                val dest = if (rangeDestTest) "Test" else "Backup"
                tvProgress.text = "Stopped — ${rangeUnion.size} stable kept in $dest"
                return@setOnClickListener
            }
            val cidrs = etCidrs.text.toString().trim()
            if (cidrs.isEmpty()) {
                toast("Enter a CIDR (e.g. 162.159.192.11)")
                return@setOnClickListener
            }
            rangeCidrs = cidrs
            rangePorts = etPorts.text.toString().trim().ifEmpty { "500" }
            rangeDestTest = cbTest.isChecked
            added = 0
            rangePass = 1
            rangeUnion.clear()
            rangeAdapter.items = emptyList()
            rangeStopped = false
            btnScan.text = "■ Stop"
            tvProgress.text = "Pass 1/$RANGE_PASSES — starting…"
            runRangePass()
        }
    }

    /**
     * Deep-link from the Stable tab ("explore this winner's /24"):
     * pre-fill CIDR + port and auto-start the neighbor scan. Consumed
     * (cleared) here so a later return to this tab does not re-fire it.
     * Called on creation and on every return (tabs are shown/hidden,
     * not recreated — v1.23).
     */
    private fun consumeExplore() {
        val exploreCidr = vm.pendingExploreCidr ?: return
        vm.pendingExploreCidr = null
        val explorePort = vm.pendingExplorePort ?: "500"
        vm.pendingExplorePort = null
        programmaticCidr = true
        etCidrs.setText(exploreCidr)
        programmaticCidr = false
        etPorts.setText(explorePort)
        if (engine.running) {
            // A run is already going (tabs no longer kill it): pre-fill
            // only — auto-starting would toggle-stop the live run.
            toast("Range scan running — explore pre-filled, tap Scan when it finishes")
            return
        }
        toast("Exploring ${exploreCidr}…")
        etCidrs.post { btnScan.performClick() }
    }

    /**
     * One of the 4 range passes: a fresh engine process (fresh flows)
     * over the same CIDRs. Stable (0%-loss) answers join the live
     * union at once (v1.30: shown + saved on landing, run stops at 10).
     */
    private fun runRangePass() {
        engine.scan(
            ScanHelper.rangeScanArgs(rangeCidrs, rangePorts, 50),
            onResult = { r ->
                if (ScanHelper.isStable(r)) {
                    // v1.30: show + save IMMEDIATELY on landing — no
                    // waiting for pass 4, and Stop loses nothing.
                    val appCtx = activity?.applicationContext
                    if (appCtx != null) {
                        rangeUnion[r.endpoint] = r
                        saveRangeOne(appCtx, r)
                    }
                    activity?.runOnUiThread {
                        rangeAdapter.items = rangeUnion.values.toList()
                        if (rangeStopped) return@runOnUiThread
                        tvProgress.text = "Pass $rangePass/$RANGE_PASSES — ${rangeUnion.size} stable…"
                        // v1.30: stop as soon as 10 distinct stable
                        // endpoints are collected (one pass or accumulated).
                        if (rangeUnion.size >= RANGE_STOP_AT) {
                            rangeStopped = true
                            engine.cancel()
                            btnScan.text = "Scan range"
                            val dest = if (rangeDestTest) "Test" else "Backup"
                            tvProgress.text = "Stopped — 10 stable saved to $dest"
                        }
                    }
                }
            },
            onProgress = { msg ->
                activity?.runOnUiThread {
                    if (!rangeStopped) tvProgress.text = "Pass $rangePass/$RANGE_PASSES · $msg"
                }
            },
            onDone = { ok, err ->
                val act = activity ?: return@scan
                act.runOnUiThread {
                    if (rangeStopped) {
                        btnScan.text = "Scan range"
                        return@runOnUiThread
                    }
                    if (!ok) {
                        btnScan.text = "Scan range"
                        tvProgress.text = "Error: ${err ?: "unknown"}"
                        Toast.makeText(act.applicationContext, "Error: ${err ?: "unknown"}", Toast.LENGTH_SHORT).show()
                        return@runOnUiThread
                    }
                    if (rangePass < RANGE_PASSES) {
                        rangePass++
                        tvProgress.text = "Pass $rangePass/$RANGE_PASSES — starting…"
                        runRangePass()
                    } else {
                        finishRangePasses(act)
                    }
                }
            }
        )
    }

    /** One stable landing's save (v1.30): called the moment the endpoint
     *  proves stable, not at the end of the run — so Stop and the
     *  10-stop keep everything found so far. Store min-ms invariant
     *  still applies inside the stores. */
    private fun saveRangeOne(ctx: android.content.Context, r: ScanResult) {
        val entry = BackupEntry(
            ip = r.ip, port = r.port, ms = r.latencyMs,
            jitterMs = r.jitterMs, savedAt = System.currentTimeMillis()
        )
        if (rangeDestTest) TestStore.add(ctx, entry) else BackupStore.add(ctx, entry)
        // Step 1 (state machine): stable in 1+ of 4 passes = working evidence.
        EndpointStateStore.markWorking(ctx, r.ip)
    }

    /**
     * End of a full 4-pass run (v1.30): saves already happened per
     * landing, so this only reports the union total. A failed pass
     * saves nothing on its own.
     */
    private fun finishRangePasses(act: android.app.Activity) {
        btnScan.text = "Scan range"
        val ctx = act.applicationContext
        added = rangeUnion.size
        val dest = if (rangeDestTest) "Test" else "Backup"
        tvProgress.text = "Done — $added stable in 1+ of 4 passes"
        Toast.makeText(ctx, "Done — added $added to $dest (stable in 1+ of 4 passes)", Toast.LENGTH_SHORT).show()
    }

    /**
     * Fill the CIDR field from the latest verified scan (/24s, rank order).
     * Never overwrites the user's own manual input.
     */
    private fun refreshCidrField() {
        if (vm.rangeCidrsManual) return
        val cidrs = vm.verifiedCidrs.ifEmpty { ScanHelper.DEFAULT_RANGE_CIDRS }
        programmaticCidr = true
        etCidrs.setText(cidrs)
        programmaticCidr = false
    }

    private fun toast(m: String) =
        Toast.makeText(requireContext(), m, Toast.LENGTH_SHORT).show()

    /**
     * Tabs are shown/hidden, not recreated (v1.23) — on return, consume
     * a pending Stable explore link, and re-apply the auto-fill rule
     * (verified CIDRs unless the user typed their own).
     */
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && ::etCidrs.isInitialized) {
            consumeExplore()
            refreshCidrField()
        }
    }

    override fun onDestroyView() {
        rangeStopped = true
        engine.cancel()
        super.onDestroyView()
    }
}
