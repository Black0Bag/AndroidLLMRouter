package com.llmrouter.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 渠道实体 = 一个 URL（对应 NEW API 的 Channel）
 * 每个 Channel 包含：BaseURL + 多个 API Key + 多个模型 + 优先级 + 状态 + AutoBan
 */
@Entity(tableName = "channels")
data class ChannelEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val baseUrl: String,
    val apiKeys: String,           // 多个 key 用逗号分隔
    val models: String,            // 多个模型用逗号分隔
    val priority: Int = 0,         // 优先级（数字越大越优先）
    val weight: Int = 1,           // 权重（同优先级内加权随机）
    val autoBan: Boolean = true,   // 自动禁用开关
    val keyMode: String = "random",// 密钥轮换模式: random / polling
    val testModel: String = "",    // 测试用模型名
    val status: Int = 1,           // 1=启用, 2=手动禁用, 3=自动禁用
    val responseTime: Int = 0,     // 最近响应时间（毫秒）
    val testTime: Long = 0,        // 最后测试时间戳
    val keyStates: String = "[]",  // 各 Key 状态 JSON: [{index, enabled, disabledReason, disabledTime}]
    val pollingIndex: Int = 0,     // 轮询当前索引
    val usedQuota: Long = 0,       // 已用请求数
    val createdAt: Long = System.currentTimeMillis()
) {
    /** 获取 Key 列表 */
    fun keyList(): List<String> =
        if (apiKeys.isBlank()) emptyList()
        else apiKeys.split(",").map { it.trim() }.filter { it.isNotEmpty() }

    /** 获取模型列表 */
    fun modelList(): List<String> =
        if (models.isBlank()) emptyList()
        else models.split(",").map { it.trim() }.filter { it.isNotEmpty() }

    /** 是否包含指定模型（支持归一化匹配） */
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

/**
 * 路由日志实体
 */
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
