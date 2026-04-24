package com.localyze.ui.viewmodels

import com.localyze.domain.models.Memory
import com.localyze.data.billing.PremiumSubscriptionState

data class SettingsUiState(
    val darkMode: Boolean = false,
    val thinkingMode: Boolean = false,
    val streamTokens: Boolean = true,
    val voiceAutoPlay: Boolean = false,
    val allowWebSearch: Boolean = false,
    val memoryEnabled: Boolean = false,
    val allowCellularDownload: Boolean = false,
    val memoryCount: Int = 0,
    val memories: List<Memory> = emptyList(),
    val modelInfo: ModelInfo = ModelInfo(),
    val storageInfo: StorageInfo = StorageInfo(),
    val isMemorySectionExpanded: Boolean = false,
    val memorySearchQuery: String = "",
    val searchResults: List<Memory> = emptyList(),
    val memoryTransparencyText: String = "",
    val showDeleteModelDialog: Boolean = false,
    val showClearMemoriesDialog: Boolean = false,
    val premiumSubscription: PremiumSubscriptionState = PremiumSubscriptionState()
)

data class ModelInfo(
    val isLoaded: Boolean = false,
    val isDownloaded: Boolean = false,
    val modelSizeMb: Long = 0L,
    val modelName: String = "Gemma 4 E4B",
    val quantization: String = "INT4",
    val contextWindow: String = "4096 tokens configured"
)

data class StorageInfo(
    val availableStorageGb: Float = 0f,
    val modelSizeMb: Long = 0L,
    val databaseSizeMb: Float = 0f,
    val totalUsedMb: Float = 0f
)
