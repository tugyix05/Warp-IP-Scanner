package com.phoenix.warpscanner

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * Range tab live rows (v1.30): one row per distinct stable endpoint,
 * shown the moment it lands. Reuses the result row layout with the
 * action buttons hidden — Range rows are display-only; saves already
 * happened at landing time.
 */
class RangeStableAdapter : RecyclerView.Adapter<RangeStableAdapter.VH>() {

    var items: List<ScanResult> = emptyList()
        set(v) { field = v; notifyDataSetChanged() }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val rank: TextView = v.findViewById(R.id.tvRank)
        val ip: TextView = v.findViewById(R.id.tvIp)
        val tag: TextView = v.findViewById(R.id.tvTag)
        val ms: TextView = v.findViewById(R.id.tvMs)
        val copy: View = v.findViewById(R.id.btnCopy)
        val range: View = v.findViewById(R.id.btnRange)
    }

    override fun onCreateViewHolder(p: ViewGroup, vt: Int): VH =
        VH(LayoutInflater.from(p.context).inflate(R.layout.item_result, p, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val r = items[pos]
        h.rank.text = (pos + 1).toString()
        h.ip.text = r.endpoint
        h.ms.text = r.latencyMs?.let { "$it ms" } ?: "? ms"
        h.tag.text = r.jitterMs?.let { "±$it ms" } ?: "stable"
        h.copy.visibility = View.GONE
        h.range.visibility = View.GONE
    }
}
