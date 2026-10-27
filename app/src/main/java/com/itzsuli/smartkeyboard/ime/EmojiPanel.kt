package com.itzsuli.smartkeyboard.ime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.itzsuli.smartkeyboard.data.EmojiData
import com.itzsuli.smartkeyboard.data.EmojiGroup

/** Emoji picker with recents, categories and skin-tone variants (long press). */
@SuppressLint("ViewConstructor")
class EmojiPanel(
    context: Context,
    private val data: EmojiData,
    private val recents: MutableList<String>,
    private val onEmoji: (String) -> Unit,
    private val onBack: () -> Unit,
    private val onDelete: () -> Unit,
) : LinearLayout(context) {

    private val density = resources.displayMetrics.density
    private val grid = RecyclerView(context)
    private val tabs = LinearLayout(context)
    private var theme = KeyboardTheme.DARK
    private val sections = ArrayList<Pair<String, Int>>() // (icon, adapter position)
    private val items = ArrayList<Any>() // String header or String emoji
    private val variants = HashMap<String, List<String>>()

    init {
        orientation = VERTICAL
        buildItems()
        val columns = 8
        val lm = GridLayoutManager(context, columns)
        lm.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int) = if (items[position] is Header) columns else 1
        }
        grid.layoutManager = lm
        grid.adapter = Adapter()
        grid.setHasFixedSize(true)
        addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        grid.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) = highlightTab(lm.findFirstVisibleItemPosition())
        })

        val bottom = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        bottom.addView(button("ABC") { onBack() }, LayoutParams((56 * density).toInt(), LayoutParams.MATCH_PARENT))
        val scroller = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false }
        tabs.orientation = HORIZONTAL
        scroller.addView(tabs)
        bottom.addView(scroller, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        bottom.addView(button("⌫") { onDelete() }, LayoutParams((56 * density).toInt(), LayoutParams.MATCH_PARENT))
        addView(bottom, LayoutParams(LayoutParams.MATCH_PARENT, (44 * density).toInt()))
        buildTabs()
    }

    private class Header(val title: String)

    private fun buildItems() {
        items.clear(); sections.clear()
        if (recents.isNotEmpty()) {
            sections.add("🕘" to items.size)
            items.add(Header("Recent"))
            items.addAll(recents)
        }
        for (g: EmojiGroup in data.groups) {
            sections.add(g.icon to items.size)
            items.add(Header(g.name))
            for (e in g.emoji) {
                items.add(e.value)
                if (e.variants.isNotEmpty()) variants[e.value] = e.variants
            }
        }
    }

    private fun buildTabs() {
        tabs.removeAllViews()
        for ((icon, pos) in sections) {
            tabs.addView(TextView(context).apply {
                text = icon
                textSize = 20f
                gravity = Gravity.CENTER
                setOnClickListener { (grid.layoutManager as GridLayoutManager).scrollToPositionWithOffset(pos, 0) }
            }, LayoutParams((46 * density).toInt(), LayoutParams.MATCH_PARENT))
        }
        highlightTab(0)
    }

    private fun highlightTab(firstVisible: Int) {
        val active = sections.indexOfLast { it.second <= firstVisible }.coerceAtLeast(0)
        for (i in 0 until tabs.childCount) {
            tabs.getChildAt(i).background = if (i == active) GradientDrawable().apply {
                cornerRadius = 12 * density
                setColor(Color.argb(50, Color.red(theme.accent), Color.green(theme.accent), Color.blue(theme.accent)))
            } else null
        }
    }

    private fun button(label: String, action: () -> Unit) = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setOnClickListener { action() }
    }

    fun applyTheme(t: KeyboardTheme) {
        theme = t
        setBackgroundColor(t.background)
        val bottom = getChildAt(1) as ViewGroup
        (bottom.getChildAt(0) as TextView).setTextColor(t.functionText)
        (bottom.getChildAt(2) as TextView).setTextColor(t.functionText)
        grid.adapter?.notifyDataSetChanged()
        highlightTab((grid.layoutManager as GridLayoutManager).findFirstVisibleItemPosition())
    }

    private fun pick(emoji: String) {
        recents.remove(emoji)
        recents.add(0, emoji)
        while (recents.size > 32) recents.removeAt(recents.size - 1)
        onEmoji(emoji)
    }

    private fun showVariants(anchor: View, base: String) {
        val options = listOf(base) + (variants[base] ?: return)
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            background = GradientDrawable().apply {
                cornerRadius = 14 * density
                setColor(if (theme.isDark) theme.functionKey or 0xFF000000.toInt() else Color.WHITE)
            }
            elevation = 8 * density
        }
        val popup = PopupWindow(row, LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, true)
        for (o in options) {
            row.addView(TextView(context).apply {
                text = o
                textSize = 26f
                gravity = Gravity.CENTER
                setPadding((8 * density).toInt(), (6 * density).toInt(), (8 * density).toInt(), (6 * density).toInt())
                setOnClickListener { pick(o); popup.dismiss() }
            })
        }
        popup.showAsDropDown(anchor, 0, -(anchor.height * 2.2f).toInt())
    }

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemViewType(position: Int) = if (items[position] is Header) 0 else 1
        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val tv = TextView(parent.context)
            if (viewType == 0) {
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                tv.setPadding((12 * density).toInt(), (8 * density).toInt(), 0, (4 * density).toInt())
                tv.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            } else {
                tv.textSize = 26f
                tv.gravity = Gravity.CENTER
                tv.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (46 * density).toInt())
                tv.background = android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(Color.argb(40, 128, 128, 128)), null, null,
                )
            }
            return object : RecyclerView.ViewHolder(tv) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val tv = holder.itemView as TextView
            when (val item = items[position]) {
                is Header -> {
                    tv.text = item.title
                    tv.setTextColor(theme.hintText)
                }
                is String -> {
                    tv.text = item
                    tv.setOnClickListener { pick(item) }
                    tv.setOnLongClickListener { showVariants(tv, item); true }
                }
            }
        }
    }
}
