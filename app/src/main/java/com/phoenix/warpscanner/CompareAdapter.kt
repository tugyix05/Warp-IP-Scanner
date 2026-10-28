package com.phoenix.warpscanner

import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * Compare-mode list (v1.26): BACKUP and TEST sections in one list.
 * Row binding mirrors [StableAdapter] (checkbox, rank, IP, tag, ms
 * badge with the same color thresholds) but without copy/explore
 * buttons, per the compare mockup. Section headers and empty-section
 * placeholders are separate view types.
 */
class CompareAdapter(
    private val goodCount: (String) -> Int,
    private val isSelected: (String) -> Boolean,
    private val onToggleSelect: (String, Boolean) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    sealed class Item {
        data class Header(val title: String) : Item()
        data class Row(val result: ScanResult, val rank: Int, val best: Boolean) : Item()
        data class Empty(val text: String) : Item()
    }

    var items: List<Item> = emptyList()
        set(v) { field = v; notifyDataSetChanged() }

    class HeaderVH(v: View) : RecyclerView.ViewHolder(v) {
        val text: TextView = v.findViewById(R.id.tvCompareHeader)
    }

    class RowVH(v: View) : RecyclerView.ViewHolder(v) {
        val select: CheckBox = v.findViewById(R.id.cbSelect)
        val rank: TextView = v.findViewById(R.id.tvRank)
        val ip: TextView = v.findViewById(R.id.tvIp)
        val tag: TextView = v.findViewById(R.id.tvTag)
        val ms: TextView = v.findViewById(R.id.tvMs)
    }

    override fun getItemViewType(pos: Int): Int = when (items[pos]) {
        is Item.Header -> 0
        is Item.Row -> 1
        is Item.Empty -> 2
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(p: ViewGroup, vt: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(p.context)
        return when (vt) {
            1 -> RowVH(inf.inflate(R.layout.item_compare, p, false))
            else -> HeaderVH(inf.inflate(R.layout.item_compare_header, p, false))
        }
    }

    override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
        when (val it = items[pos]) {
            is Item.Header -> {
                val vh = h as HeaderVH
                vh.text.text = it.title
                vh.text.setTextColor(Color.parseColor("#4A90D9"))
                vh.text.setTypeface(null, Typeface.BOLD)
                vh.text.gravity = Gravity.START
            }
            is Item.Empty -> {
                val vh = h as HeaderVH
                vh.text.text = it.text
                vh.text.setTextColor(Color.parseColor("#9AA0A8"))
                vh.text.setTypeface(null, Typeface.NORMAL)
                vh.text.gravity = Gravity.CENTER
            }
            is Item.Row -> {
                val vh = h as RowVH
                val r = it.result
                vh.select.setOnCheckedChangeListener(null)
                vh.select.isChecked = isSelected(r.endpoint)
                vh.select.setOnCheckedChangeListener { _, checked ->
                    onToggleSelect(r.endpoint, checked)
                }
                vh.rank.text = it.rank.toString()
                vh.ip.text = r.endpoint
                val tags = mutableListOf<String>()
                if (it.best) tags.add("★ BEST")
                if (!ScanHelper.isListed(r.ip)) tags.add("UNLISTED")
                if (r.jitterMs != null) tags.add("±${r.jitterMs}ms")
                val n = goodCount(r.endpoint)
                if (n > 0) tags.add("✓ $n times") else tags.add("✓ new")
                vh.tag.visibility = View.VISIBLE
                vh.tag.text = tags.joinToString(" · ")
                val ms = r.latencyMs
                vh.ms.text = if (ms != null) "$ms ms" else "? ms"
                vh.ms.setBackgroundColor(
                    when {
                        ms == null -> Color.parseColor("#3A3A3E")
                        ms < 100 -> Color.parseColor("#1B5E20")
                        ms < 160 -> Color.parseColor("#7A5C00")
                        else -> Color.parseColor("#8A3B12")
                    }
                )
            }
        }
    }
}
