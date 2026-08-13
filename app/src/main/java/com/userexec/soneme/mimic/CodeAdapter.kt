package com.userexec.soneme.mimic

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.TextView

class CodeAdapter(private val context: Context) : BaseAdapter() {
    var items: List<CodeRecord> = emptyList()
        set(value) { field = value; notifyDataSetChanged() }

    override fun getCount() = items.size
    override fun getItem(position: Int) = items[position]
    override fun getItemId(position: Int) = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val row = (convertView as? LinearLayout) ?: LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isFocusable = false
            setPadding(dp(8), dp(4), dp(8), dp(4))
            minimumHeight = dp(50)
            addView(TextView(context).apply {
                id = android.R.id.text1
                textSize = 17f
                setTypeface(typeface, Typeface.BOLD)
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.MARQUEE
                marqueeRepeatLimit = -1
                gravity = Gravity.CENTER_VERTICAL
            })
            addView(TextView(context).apply {
                id = android.R.id.text2
                textSize = 12f
                isSingleLine = true
            })
        }
        val item = items[position]
        row.findViewById<TextView>(android.R.id.text1).apply {
            text = item.name
            isSelected = parent is android.widget.ListView && parent.selectedItemPosition == position
        }
        row.findViewById<TextView>(android.R.id.text2).text = Formats.displayLabel(item)
        return row
    }

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density + 0.5f).toInt()
}
