package com.fsck.k9.ui.managefolders

import androidx.preference.PreferenceDataStore
import app.k9mail.legacy.mailstore.FolderRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import net.thunderbird.feature.account.AccountId
import net.thunderbird.feature.mail.folder.api.FolderDetails

class FolderSettingsDataStore(
    private val folderRepository: FolderRepository,
    private val accountId: AccountId,
    private var folder: FolderDetails,
    private val saveScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : PreferenceDataStore() {

    override fun getBoolean(key: String?, defValue: Boolean): Boolean {
        return when (key) {
            "folder_settings_in_top_group" -> folder.isInTopGroup
            "folder_settings_include_in_integrated_inbox" -> folder.isIntegrate
            "folder_settings_sync" -> folder.isSyncEnabled
            "folder_settings_notifications" -> folder.isNotificationsEnabled
            "folder_settings_push" -> folder.isPushEnabled
            "folder_settings_visible" -> folder.isVisible
            "folder_settings_aggregate_tab" -> folder.isAggregateTab
            else -> error("Unknown key: $key")
        }
    }

    override fun putBoolean(key: String?, value: Boolean) {
        return when (key) {
            "folder_settings_in_top_group" -> updateFolder(folder.copy(isInTopGroup = value))
            "folder_settings_include_in_integrated_inbox" -> updateFolder(folder.copy(isIntegrate = value))
            "folder_settings_sync" -> updateFolder(folder.copy(isSyncEnabled = value))
            "folder_settings_notifications" -> updateFolder(folder.copy(isNotificationsEnabled = value))
            "folder_settings_push" -> updateFolder(folder.copy(isPushEnabled = value))
            "folder_settings_visible" -> updateFolder(folder.copy(isVisible = value))
            "folder_settings_aggregate_tab" -> setAggregateTab(value)
            else -> error("Unknown key: $key")
        }
    }

    /**
     * Turning a folder into an inbox tab only makes sense if the folder also syncs, is visible, and
     * sits in the top group, so enabling the tab cascades all three on. They are applied in a single
     * [updateFolder] call: [updateFolderSettings] rewrites the whole folder row, so issuing separate
     * writes for each flag could race and clobber one another. Turning the tab back off is left
     * narrow - it does not undo settings the user may want to keep.
     */
    private fun setAggregateTab(enabled: Boolean) {
        if (enabled) {
            updateFolder(
                folder.copy(
                    isAggregateTab = true,
                    isSyncEnabled = true,
                    isVisible = true,
                    isInTopGroup = true,
                ),
            )
        } else {
            updateFolder(folder.copy(isAggregateTab = false))
        }
    }

    private fun updateFolder(newFolder: FolderDetails) {
        folder = newFolder
        saveScope.launch {
            folderRepository.updateFolderDetails(accountId, newFolder)
        }
    }
}
