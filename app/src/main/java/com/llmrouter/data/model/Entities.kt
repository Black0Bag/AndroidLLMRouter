package com.llmrouter.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import org.json.JSONObject

/**
 * 渠道实体 = 一个 URL（对应 NEW API 的 Channel）
 *
 * v0.6.0 新增字段：
 * - type: 渠道类型（openai/azure/claude/gemini/custom），影响 API 格式适配
 * - modelMapping: 模型映射 JSON，如 {"gpt-4":"gpt-4o"}，请求时自动替换模型名
 * - statusCodeMapping: 状态码映射 JSON，如 {"429":"529"}
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
    val createdAt: Long = System.currentTimeMillis(),
    // === v0.6.0 新增 ===
    val type: String = "openai",           // 渠道类型
    val modelMapping: String = "",         // 模型映射 JSON: {"gpt-4":"gpt-4o"}
    val statusCodeMapping: String = ""     // 状态码映射 JSON: {"429":"529"}
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

    /** v0.7.2: 缓存的映射 JSON（避免每次请求都 new JSONObject 重复解析） */
    private val modelMappingJson: JSONObject? by lazy {
        if (modelMapping.isBlank()) null
        else try { JSONObject(modelMapping) } catch (e: Exception) { null }
    }

    /**
     * 应用模型映射：将请求中的模型名替换为映射后的名称
     * 例如 mapping = {"gpt-4":"gpt-4o"}，请求 gpt-4 -> 上游收到 gpt-4o
     * 支持链式映射，带循环检测（最多 10 层）
     */
    fun applyModelMapping(model: String): String {
        val mapping = modelMappingJson ?: return model
        var current = model
        val visited = mutableSetOf(model.lowercase())
        // 链式映射，最多 10 层防止死循环
        for (i in 0 until 10) {
            val mapped = mapping.optString(current, null) ?: break
            if (mapped.lowercase() in visited) break // 循环检测
            visited.add(mapped.lowercase())
            current = mapped
        }
        return current
    }

    /** v0.7.2: 缓存的状态码映射 JSON */
    private val statusCodeMappingJson: JSONObject? by lazy {
        if (statusCodeMapping.isBlank()) null
        else try { JSONObject(statusCodeMapping) } catch (e: Exception) { null }
    }

    /**
     * 应用状态码映射：将上游返回的状态码映射为自定义状态码
     * 例如 mapping = {"429":"529"}，上游 429 -> 客户端收到 529
     */
    fun applyStatusCodeMapping(statusCode: Int): Int {
        val mapping = statusCodeMappingJson ?: return statusCode
        return mapping.optString(statusCode.toString(), null)?.toIntOrNull() ?: statusCode
    }

    companion object {
        const val STATUS_ENABLED = 1
        const val STATUS_MANUAL_DISABLED = 2
        const val STATUS_AUTO_BANNED = 3

        /** 渠道类型列表 */
        val CHANNEL_TYPES = listOf(
            "openai" to "OpenAI",
            "azure" to "Azure OpenAI",
            "claude" to "Anthropic Claude",
            "gemini" to "Google Gemini",
            "custom" to "自定义"
        )
    }
}

/**
 * 路由日志实体
 *
 * v0.6.0 新增字段：
 * - inputTokens / outputTokens / totalTokens: Token 消费详情
 * - statusCode: HTTP 状态码
 * - apiEndpoint: 请求的端点路径（如 /v1/chat/completions）
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
    val retryCount: Int = 0,
    // === v0.6.0 新增 ===
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val totalTokens: Int = 0,
    val statusCode: Int = 0,
    val apiEndpoint: String = ""
)
