package com.fsck.k9.ui.messagelist

/**
 * A Gmail-style aggregate "tab" shown at the top of an inbox-like message list.
 *
 * It represents a folder that has been flagged to act as an aggregate tab (see
 * [net.thunderbird.feature.mail.folder.api.FolderDetails.isAggregateTab]). The tab summarises the
 * unread messages in that folder and acts as a shortcut to open it. Tabs are only produced for
 * folders that currently have at least one unread message.
 */
data class AggregateFolderTab(
    val accountUuid: String,
    val folderId: Long,
    val displayName: String,
    val unreadCount: Int,
    /** Display name of the sender of the most recent unread message ("" if unknown). */
    val sender: String,
    /** Subject of the most recent unread message ("" if none). */
    val subject: String,
) {
    /**
     * Stable id for use as a RecyclerView item id. Derived from the account and folder so it stays
     * constant for a given tab across list updates and is distinct from message item ids.
     */
    val viewId: Long = "aggregate_tab:$accountUuid:$folderId".hashCode().toLong()
}
