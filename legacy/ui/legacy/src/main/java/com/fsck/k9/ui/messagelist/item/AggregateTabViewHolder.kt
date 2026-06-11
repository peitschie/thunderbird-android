package com.fsck.k9.ui.messagelist.item

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.text.inSpans
import androidx.core.view.isVisible
import com.fsck.k9.ui.R
import com.fsck.k9.ui.messagelist.AggregateFolderTab
import com.google.android.material.color.MaterialColors
import com.google.android.material.textview.MaterialTextView

class AggregateTabViewHolder private constructor(
    view: View,
    private val onClick: (AggregateFolderTab) -> Unit,
) : MessageListViewHolder(view) {
    private val iconView: ImageView = view.findViewById(R.id.aggregate_tab_icon)
    private val nameView: MaterialTextView = view.findViewById(R.id.aggregate_tab_name)
    private val sendersView: MaterialTextView = view.findViewById(R.id.aggregate_tab_senders)
    private val countView: MaterialTextView = view.findViewById(R.id.aggregate_tab_count)

    private var boundTab: AggregateFolderTab? = null

    init {
        view.setOnClickListener { boundTab?.let(onClick) }
    }

    fun bind(tab: AggregateFolderTab) {
        boundTab = tab
        nameView.text = tab.displayName

        val summary = buildSummary(tab)
        sendersView.text = summary
        sendersView.isVisible = summary.isNotEmpty()

        countView.text = itemView.resources.getQuantityString(
            R.plurals.message_list_aggregate_tab_new_count,
            tab.unreadCount,
            tab.unreadCount,
        )

        // The icon circle and the count pill share a single accent colour derived from the folder.
        val accentColor = ColorStateList.valueOf(colorFor(tab))
        val onAccentColor = ColorStateList.valueOf(Color.WHITE)
        iconView.backgroundTintList = accentColor
        iconView.imageTintList = onAccentColor
        countView.backgroundTintList = accentColor
        countView.setTextColor(Color.WHITE)
    }

    /**
     * Gmail-style preview line: the sender in bold/primary text colour, followed by the subject in
     * the muted secondary colour. Returns an empty sequence when there is no sender to show.
     */
    private fun buildSummary(tab: AggregateFolderTab): CharSequence {
        if (tab.sender.isEmpty()) return ""

        val primaryColor = MaterialColors.getColor(itemView, com.google.android.material.R.attr.colorOnSurface)
        val mutedColor = MaterialColors.getColor(itemView, com.google.android.material.R.attr.colorOnSurfaceVariant)

        return SpannableStringBuilder().apply {
            inSpans(StyleSpan(Typeface.BOLD), ForegroundColorSpan(primaryColor)) {
                append(tab.sender)
            }
            if (tab.subject.isNotEmpty()) {
                inSpans(ForegroundColorSpan(mutedColor)) {
                    append(" — ")
                    append(tab.subject)
                }
            }
        }
    }

    companion object {
        // A small fixed palette of saturated colours that read well with white foreground in both
        // light and dark themes. A folder is assigned one deterministically by hashing its name, so
        // the same folder keeps a stable colour without needing any stored per-folder configuration.
        private val PALETTE = intArrayOf(
            0xFFD32F2F.toInt(), // red
            0xFF8E24AA.toInt(), // purple
            0xFF3949AB.toInt(), // indigo
            0xFF1E88E5.toInt(), // blue
            0xFF00897B.toInt(), // teal
            0xFF43A047.toInt(), // green
            0xFFF57C00.toInt(), // orange
            0xFF6D4C41.toInt(), // brown
        )

        private fun colorFor(tab: AggregateFolderTab): Int {
            val key = tab.displayName.lowercase().ifEmpty { tab.folderId.toString() }
            return PALETTE[key.hashCode().mod(PALETTE.size)]
        }

        fun create(
            layoutInflater: LayoutInflater,
            parent: ViewGroup,
            onClick: (AggregateFolderTab) -> Unit,
        ): AggregateTabViewHolder {
            val view = layoutInflater.inflate(R.layout.message_list_item_aggregate_tab, parent, false)
            return AggregateTabViewHolder(view, onClick)
        }
    }
}
