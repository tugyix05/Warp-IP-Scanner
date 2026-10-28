package com.phoenix.warpscanner

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView

/**
 * Tab host: fragments are SHOWN/HIDDEN, never replaced. Replacing the
 * fragment on every tab switch destroyed its view, and every tab's
 * onDestroyView cancels its engine — so a running scan died the moment
 * the user looked at another tab. With show/hide, a running scan keeps
 * running (its engine process and wake lock are untouched) and the tab
 * shows live progress when the user comes back. Fragments that need
 * fresh data on return (Backup's lists, Range's explore link) refresh
 * themselves in onHiddenChanged.
 */
class MainActivity : AppCompatActivity() {

    private val fragments = mutableMapOf<Int, Fragment>()
    private var currentTab = R.id.nav_scan

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val nav = findViewById<BottomNavigationView>(R.id.bottomNav)
        nav.setOnItemSelectedListener { item ->
            showTab(item.itemId)
            true
        }
        if (savedInstanceState != null) {
            // Activity recreated (rotation/process death): re-attach to
            // the fragments the FragmentManager restored; it restores
            // their shown/hidden state itself, so no transaction here.
            // Tags first; fragments restored from an older version
            // (pre-tag, e.g. v1.22's replace()) have none, so fall back
            // to matching by class — otherwise a fresh fragment would
            // be added on top of the restored one.
            currentTab = savedInstanceState.getInt(KEY_TAB, R.id.nav_scan)
            for ((id, tag) in TAB_TAGS) {
                supportFragmentManager.findFragmentByTag(tag)?.let { fragments[id] = it }
            }
            for (f in supportFragmentManager.fragments) {
                val id = when (f) {
                    is ScanFragment -> R.id.nav_scan
                    is RangeFragment -> R.id.nav_range
                    is BackupFragment -> R.id.nav_backup
                    is StableFragment -> R.id.nav_stable
                    else -> null
                }
                if (id != null) fragments.putIfAbsent(id, f)
            }
        } else if (nav.selectedItemId == R.id.nav_scan) {
            // Menu already on Scan: setting the same id fires no event.
            showTab(R.id.nav_scan)
        } else {
            nav.selectedItemId = R.id.nav_scan
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_TAB, currentTab)
    }

    /**
     * Show one tab, hide the current one. commitNow so the fragment is
     * added synchronously — a second tap before an async commit executes
     * would otherwise add the same fragment twice.
     */
    private fun showTab(id: Int) {
        val tx = supportFragmentManager.beginTransaction()
        fragments[currentTab]?.let { if (it.isAdded) tx.hide(it) }
        val f = fragments.getOrPut(id) { newTab(id) }
        if (f.isAdded) tx.show(f) else tx.add(R.id.container, f, TAB_TAGS[id])
        tx.commitNow()
        currentTab = id
    }

    private fun newTab(id: Int): Fragment = when (id) {
        R.id.nav_range -> RangeFragment()
        R.id.nav_backup -> BackupFragment()
        R.id.nav_stable -> StableFragment()
        else -> ScanFragment()
    }

    companion object {
        private const val KEY_TAB = "current_tab"
        private val TAB_TAGS = mapOf(
            R.id.nav_scan to "tab_scan",
            R.id.nav_range to "tab_range",
            R.id.nav_backup to "tab_backup",
            R.id.nav_stable to "tab_stable"
        )
    }
}
