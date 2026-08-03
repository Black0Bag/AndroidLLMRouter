package com.llmrouter.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 渠道实体 = 一个 URL（对应 NEW API 的 Channel）
 */
@Entity(tableName = "channels")
data class ChannelEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val baseUrl: String,
    val apiKeys: String,           // 多个 key 用逗号分隔（向后兼容）
    val models: String,            // 从服务器拉取的全部模型，逗号分隔
    val disabledModels: String = "", // 被用户排除的模型，逗号分隔
    val priority: Int = 0,
    val weight: Int = 1,
    val autoBan: Boolean = true,
    val keyMode: String = "random",
    val testModel: String = "",
    val status: Int = 1,
    val responseTime: Int = 0,
    val testTime: Long = 0,
    val keyStates: String = "[]",
    val pollingIndex: Int = 0,
    val usedQuota: Long = 0,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun keyList(): List<String> =
        if (apiKeys.isBlank()) emptyList()
        else apiKeys.split(",").map { it.trim() }.filter { it.isNotEmpty() }

    /** 服务器拉取的全部模型 */
    fun allModelList(): List<String> =
        if (models.isBlank()) emptyList()
        else models.split(",").map { it.trim() }.filter { it.isNotEmpty() }

    /** 被排除的模型 */
    fun disabledModelSet(): Set<String> =
        if (disabledModels.isBlank()) emptySet()
        else disabledModels.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()

    /** 实际参与路由的模型列表 = 全部 - 排除 */
    fun modelList(): List<String> =
        allModelList().filter { it.trim().lowercase() !in disabledModelSet() }

    fun supportsModel(model: String): Boolean {
        val normalized = model.lowercase().trim()
        return modelList().any {
            it.lowercase().trim() == normalized ||
            it.lowercase().trim().replace("-thinking", "") == normalized.replace("-thinking", "")
        }
    }

    companion object {
        const val STATUS_ENABLED = 1
        const val STATUS_MANUAL_DISABLED = 2
        const val STATUS_AUTO_BANNED = 3
    }
}

@Entity(tableName = "route_logs")
data class RouteLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val model: String,
    val channelName: String,
    val success: Boolean,
    val responseTime: Int = 0,
    val errorMessage: String? = null,
    val retryCount: Int = 0
)
