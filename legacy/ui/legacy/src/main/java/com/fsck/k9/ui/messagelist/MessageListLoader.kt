package com.fsck.k9.ui.messagelist

import app.k9mail.legacy.mailstore.MessageListRepository
import app.k9mail.legacy.mailstore.MessageStoreManager
import com.fsck.k9.contacts.ContactLetterBitmapCreator
import com.fsck.k9.helper.MessageHelper
import com.fsck.k9.mailstore.LocalStoreProvider
import com.fsck.k9.mailstore.MessageColumns
import com.fsck.k9.search.getLegacyAccounts
import net.thunderbird.core.android.account.LegacyAccount
import net.thunderbird.core.android.account.LegacyAccountManager
import net.thunderbird.core.android.account.SortType
import net.thunderbird.core.featureflag.FeatureFlagProvider
import net.thunderbird.core.logging.legacy.Log
import net.thunderbird.core.preference.display.visualSettings.message.list.MessageListPreferencesManager
import net.thunderbird.feature.mail.folder.api.OutboxFolderManager
import net.thunderbird.feature.mail.message.list.MessageListFeatureFlags
import net.thunderbird.feature.search.legacy.LocalMessageSearch
import net.thunderbird.feature.search.legacy.api.MessageSearchField
import net.thunderbird.feature.search.legacy.sql.SqlWhereClause

@Suppress("LongParameterList")
class MessageListLoader(
    private val accountManager: LegacyAccountManager,
    private val localStoreProvider: LocalStoreProvider,
    private val messageListRepository: MessageListRepository,
    private val messageStoreManager: MessageStoreManager,
    private val messageHelper: MessageHelper,
    private val messageListPreferencesManager: MessageListPreferencesManager,
    private val outboxFolderManager: OutboxFolderManager,
    private val featureFlagProvider: FeatureFlagProvider,
    private val contactLetterBitmapCreator: ContactLetterBitmapCreator,
) {

    fun getMessageList(config: MessageListConfig): MessageListInfo {
        return try {
            getMessageListInfo(config)
        } catch (e: Exception) {
            Log.e(e, "Error while fetching message list")

            // TODO: Return an error object instead of an empty list
            MessageListInfo(messageListItems = emptyList(), hasMoreMessages = false)
        }
    }

    private fun getMessageListInfo(config: MessageListConfig): MessageListInfo {
        val accounts = config.search.getLegacyAccounts(accountManager)

        // Folders flagged as aggregate tabs (regardless of unread state), per account.
        val tabFoldersByAccount = if (config.showAggregateTabs) {
            accounts.associateWith { account -> loadAggregateTabFolders(account) }
        } else {
            emptyMap()
        }

        // Messages living in an aggregate-tab folder are represented by the tab, so exclude them
        // from the (unified) inbox list to avoid showing them in both places. This is a no-op for a
        // single-account inbox, where the list only contains that folder's own messages.
        val excludedFolderIdsByAccountUuid = tabFoldersByAccount.entries.associate { (account, folders) ->
            account.uuid to folders.mapTo(mutableSetOf()) { it.id }
        }

        val messageListItems = accounts
            .flatMap { account ->
                loadMessageListForAccount(account, config, excludedFolderIdsByAccountUuid[account.uuid].orEmpty())
            }
            .filterNot { item ->
                item.folderId in excludedFolderIdsByAccountUuid[item.account.uuid].orEmpty()
            }
            .sortedWith(config)

        val hasMoreMessages = loadHasMoreMessages(accounts, config.search.folderIds)

        val aggregateTabs = buildAggregateTabs(tabFoldersByAccount)

        return MessageListInfo(messageListItems, hasMoreMessages, aggregateTabs)
    }

    private fun createMessageMapper(account: LegacyAccount): MessageListItemMapper {
        return MessageListItemMapper(
            messageHelper,
            account,
            messageListPreferencesManager,
            outboxFolderManager,
            contactLetterBitmapCreator = contactLetterBitmapCreator.takeIf {
                featureFlagProvider.provide(MessageListFeatureFlags.UseComposeForMessageListItems).isEnabled() ||
                    featureFlagProvider.provide(MessageListFeatureFlags.EnableMessageListNewState).isEnabled()
            },
        )
    }

    private fun loadMessageListForAccount(
        account: LegacyAccount,
        config: MessageListConfig,
        aggregateFolderIds: Set<Long>,
    ): List<MessageListItem> {
        val accountUuid = account.uuid
        val threadId = getThreadId(config.search)
        val sortOrder = buildSortOrder(config)
        val mapper = createMessageMapper(account)

        return when {
            threadId != null -> {
                messageListRepository.getThread(accountUuid, threadId, sortOrder, mapper)
            }

            config.showingThreadedList -> {
                val (selection, selectionArgs) = buildSelection(account, config, aggregateFolderIds)
                messageListRepository.getThreadedMessages(accountUuid, selection, selectionArgs, sortOrder, mapper)
            }

            else -> {
                val (selection, selectionArgs) = buildSelection(account, config, aggregateFolderIds)
                messageListRepository.getMessages(accountUuid, selection, selectionArgs, sortOrder, mapper)
            }
        }
    }

    private fun buildSelection(
        account: LegacyAccount,
        config: MessageListConfig,
        aggregateFolderIds: Set<Long>,
    ): Pair<String, Array<String>> {
        val query = StringBuilder()
        val queryArgs = mutableListOf<String>()

        val activeMessage = config.activeMessage
        val selectActive = activeMessage != null && activeMessage.accountUuid == account.uuid
        if (selectActive && activeMessage != null) {
            query.append("(${MessageColumns.UID} = ? AND ${MessageColumns.FOLDER_ID} = ?) OR (")
            queryArgs.add(activeMessage.uid)
            queryArgs.add(activeMessage.folderId.toString())
        }

        val whereClause = SqlWhereClause.Builder()
            .withConditions(config.search.conditions)
            .build()

        query.append(whereClause.selection)
        queryArgs.addAll(whereClause.selectionArgs)

        if (selectActive) {
            query.append(')')
        }

        var selection = query.toString()

        // Gmail-style: a categorised email exists in both the Inbox and its category folder as two
        // IMAP messages sharing one RFC Message-ID. When that category folder is an aggregate tab,
        // hide the message everywhere in this (inbox-like) list so it appears only via its tab. The
        // folder-id filter alone misses the Inbox copy, so we also exclude by Message-ID.
        if (config.showAggregateTabs && aggregateFolderIds.isNotEmpty()) {
            // Also count the Trash folder. When a categorised email is deleted from its aggregate
            // folder it is MOVED to Trash, which would otherwise un-hide the still-present Inbox copy
            // until the next server sync removes it. Keeping Trash in the set bridges that window.
            // Like the Message-ID match itself, this assumes Gmail-style label folders (where the
            // Inbox copy is the same message and is removed server-side); on plain IMAP the copies
            // are independent, so this is a no-op at best.
            val exclusionFolderIds = aggregateFolderIds + listOfNotNull(account.trashFolderId)
            val placeholders = exclusionFolderIds.joinToString(",") { "?" }
            val messageId = "messages.${MessageColumns.MESSAGE_ID}"
            val notInExcluded = "$messageId IS NULL OR $messageId NOT IN (" +
                "SELECT agg.${MessageColumns.MESSAGE_ID} FROM messages agg " +
                "WHERE agg.${MessageColumns.FOLDER_ID} IN ($placeholders) " +
                "AND agg.${MessageColumns.MESSAGE_ID} IS NOT NULL)"
            selection = if (selection.isBlank()) {
                "($notInExcluded)"
            } else {
                "($selection) AND ($notInExcluded)"
            }
            exclusionFolderIds.forEach { queryArgs.add(it.toString()) }
        }

        return selection to queryArgs.toTypedArray()
    }

    private fun getThreadId(search: LocalMessageSearch): Long? {
        return search.leafSet.firstOrNull {
            it.condition?.field == MessageSearchField.THREAD_ID
        }?.condition?.value?.toLong()
    }

    private fun buildSortOrder(config: MessageListConfig): String {
        val sortColumn = when (config.sortType) {
            SortType.SORT_ARRIVAL -> MessageColumns.INTERNAL_DATE
            SortType.SORT_ATTACHMENT -> "(${MessageColumns.ATTACHMENT_COUNT} < 1)"
            SortType.SORT_FLAGGED -> "(${MessageColumns.FLAGGED} != 1)"
            SortType.SORT_SENDER -> MessageColumns.SENDER_LIST // FIXME
            SortType.SORT_SUBJECT -> "${MessageColumns.SUBJECT} COLLATE NOCASE"
            SortType.SORT_UNREAD -> MessageColumns.READ
            SortType.SORT_DATE -> MessageColumns.DATE
        }

        val sortDirection = if (config.sortAscending) " ASC" else " DESC"
        val secondarySort = if (config.sortType == SortType.SORT_DATE || config.sortType == SortType.SORT_ARRIVAL) {
            ""
        } else {
            if (config.sortDateAscending) {
                "${MessageColumns.DATE} ASC, "
            } else {
                "${MessageColumns.DATE} DESC, "
            }
        }

        return "$sortColumn$sortDirection, $secondarySort${MessageColumns.ID} DESC"
    }

    private fun List<MessageListItem>.sortedWith(config: MessageListConfig): List<MessageListItem> {
        val comparator = when (config.sortType) {
            SortType.SORT_DATE -> {
                compareBy(config.sortAscending) { it.messageDate }
            }

            SortType.SORT_ARRIVAL -> {
                compareBy(config.sortAscending) { it.internalDate }
            }

            SortType.SORT_SUBJECT -> {
                compareStringBy<MessageListItem>(config.sortAscending) { it.subject.orEmpty() }
                    .thenByDate(config)
            }

            SortType.SORT_SENDER -> {
                compareStringBy<MessageListItem>(config.sortAscending) { it.displayName.toString() }
                    .thenByDate(config)
            }

            SortType.SORT_UNREAD -> {
                compareBy<MessageListItem>(config.sortAscending) {
                    config.sortOverrides[it.messageReference]?.isRead ?: it.isRead
                }.thenByDate(config)
            }

            SortType.SORT_FLAGGED -> {
                compareBy<MessageListItem>(!config.sortAscending) {
                    config.sortOverrides[it.messageReference]?.isStarred ?: it.isStarred
                }.thenByDate(config)
            }

            SortType.SORT_ATTACHMENT -> {
                compareBy<MessageListItem>(!config.sortAscending) { it.hasAttachments }
                    .thenByDate(config)
            }
        }.thenByDescending { it.databaseId }

        return this.sortedWith(comparator)
    }

    private fun loadHasMoreMessages(accounts: List<LegacyAccount>, folderIds: List<Long>): Boolean {
        return if (accounts.size == 1 && folderIds.size == 1) {
            val account = accounts[0]
            val folderId = folderIds[0]
            val localStore = localStoreProvider.getInstanceByLegacyAccount(account)
            val localFolder = localStore.getFolder(folderId)
            localFolder.open()
            localFolder.hasMoreMessages()
        } else {
            false
        }
    }

    /**
     * Returns the folders flagged to act as aggregate tabs for the account (all of them, regardless
     * of unread state), along with their current unread counts.
     */
    private fun loadAggregateTabFolders(account: LegacyAccount): List<AggregateTabFolder> {
        val messageStore = messageStoreManager.getMessageStore(account.uuid)

        return messageStore.getDisplayFolders(
            includeHiddenFolders = true,
            // The outbox isn't relevant here; passing null means unread counts are computed normally.
            outboxFolderId = null,
        ) { folder ->
            AggregateTabFolder(
                id = folder.id,
                name = folder.name,
                unreadCount = folder.unreadMessageCount,
                isAggregateTab = folder.isAggregateTab,
            )
        }.filter { it.isAggregateTab }
    }

    /**
     * Builds the visible tabs: one per aggregate-tab folder that currently has unread mail. Folders
     * with no unread messages produce no tab (but are still excluded from the inbox list).
     */
    private fun buildAggregateTabs(
        tabFoldersByAccount: Map<LegacyAccount, List<AggregateTabFolder>>,
    ): List<AggregateFolderTab> {
        return tabFoldersByAccount.flatMap { (account, folders) ->
            folders
                .filter { it.unreadCount > 0 }
                .map { folder ->
                    val latest = loadLatestUnread(account, folder.id)
                    AggregateFolderTab(
                        accountUuid = account.uuid,
                        folderId = folder.id,
                        displayName = folder.name,
                        unreadCount = folder.unreadCount,
                        sender = latest?.displayName?.toString()?.trim().orEmpty(),
                        subject = latest?.subject?.trim().orEmpty(),
                    )
                }
        }
    }

    /**
     * Returns the most recent unread message in the folder, used to build the Gmail-style
     * "Sender — Subject" preview line. The view truncates the result to a single line.
     */
    private fun loadLatestUnread(account: LegacyAccount, folderId: Long): MessageListItem? {
        val mapper = createMessageMapper(account)
        val selection = "${MessageColumns.FOLDER_ID} = ? AND ${MessageColumns.READ} = 0"
        val selectionArgs = arrayOf(folderId.toString())
        val sortOrder = "${MessageColumns.DATE} DESC LIMIT 1"

        return messageListRepository.getMessages(
            accountUuid = account.uuid,
            selection = selection,
            selectionArgs = selectionArgs,
            sortOrder = sortOrder,
            messageMapper = mapper,
        ).firstOrNull()
    }

    private data class AggregateTabFolder(
        val id: Long,
        val name: String,
        val unreadCount: Int,
        val isAggregateTab: Boolean,
    )
}

private inline fun <T> compareBy(sortAscending: Boolean, crossinline selector: (T) -> Comparable<*>?): Comparator<T> {
    return if (sortAscending) {
        compareBy(selector)
    } else {
        compareByDescending(selector)
    }
}

private inline fun <T> compareStringBy(sortAscending: Boolean, crossinline selector: (T) -> String): Comparator<T> {
    return if (sortAscending) {
        compareBy(String.CASE_INSENSITIVE_ORDER, selector)
    } else {
        compareByDescending(String.CASE_INSENSITIVE_ORDER, selector)
    }
}

private fun Comparator<MessageListItem>.thenByDate(config: MessageListConfig): Comparator<MessageListItem> {
    return if (config.sortDateAscending) {
        thenBy { it.messageDate }
    } else {
        thenByDescending { it.messageDate }
    }
}

data class MessageListInfo(
    val messageListItems: List<MessageListItem>,
    val hasMoreMessages: Boolean,
    val aggregateTabs: List<AggregateFolderTab> = emptyList(),
)
