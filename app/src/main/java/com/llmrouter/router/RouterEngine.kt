package com.llmrouter.router

import com.llmrouter.data.model.ChannelEntity
import com.llmrouter.data.model.ModelGroupEntity
import com.llmrouter.data.model.ModelGroupMember
import com.llmrouter.data.repo.ChannelRepository
import com.llmrouter.data.repo.ModelGroupRepository
import com.llmrouter.data.repo.SettingsRepository
import com.llmrouter.data.repo.SettingsSnapshot
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

/**
 * 核心路由引擎 — 移植 NEW API 的渠道选择/多Key轮换/AutoBan/Fallback 逻辑
 *
 * 两种路由模式：
 * - URL 维度路由：按 Priority 分层选择 URL，本 URL 内模型全不通再降级到下一 URL
 * - 模型维度路由：锁定 model 名，在所有包含该模型的 URL+Key 间按优先级/权重/轮询路由
 */
class RouterEngine(
    private val channelRepository: ChannelRepository,
    private val settingsRepository: SettingsRepository,
    private val modelGroupRepository: ModelGroupRepository? = null
) {
    /** 内存缓存：model(小写) -> 渠道列表（按 priority 降序） */
    @Volatile
    private var channelCache: Map<String, List<ChannelEntity>> = emptyMap()

    /** 全部启用渠道（按 priority 降序） */
    @Volatile
    private var allEnabledChannels: List<ChannelEntity> = emptyList()

    /** v0.8.0: 模型组缓存 name(小写) -> ModelGroupEntity */
    @Volatile
    private var modelGroupCache: Map<String, ModelGroupEntity> = emptyMap()

    /** v0.8.0: channelId -> ChannelEntity 快速查找 */
    @Volatile
    private var channelByIdCache: Map<Long, ChannelEntity> = emptyMap()

    /** 每个 channel 的 Key 轮换锁 */
    private val channelLocks = mutableMapOf<Long, Mutex>()

    private fun getLock(channelId: Long): Mutex =
        channelLocks.getOrPut(channelId) { Mutex() }

    /** 刷新缓存（每次渠道变更或启动服务时调用） */
    suspend fun refreshCache() {
        val channels = channelRepository.getEnabledChannels()
        allEnabledChannels = channels.sortedByDescending { it.priority }

        val modelMap = mutableMapOf<String, MutableList<ChannelEntity>>()
        for (ch in channels) {
            for (model in ch.modelList()) {
                modelMap.getOrPut(model.lowercase().trim()) { mutableListOf() }.add(ch)
            }
        }
        modelMap.values.forEach { list ->
            list.sortByDescending { it.priority }
        }
        channelCache = modelMap

        // v0.8.0: 同时刷新 channelId 快速查找
        channelByIdCache = channels.associateBy { it.id }

        // v0.8.0: 刷新模型组缓存
        refreshModelGroupCache()
    }

    /** v0.8.0: 刷新模型组缓存 */
    private suspend fun refreshModelGroupCache() {
        if (modelGroupRepository == null) {
            modelGroupCache = emptyMap()
            return
        }
        val groups = modelGroupRepository.getAllGroupsOnce()
        val groupMap = mutableMapOf<String, ModelGroupEntity>()
        for (g in groups) {
            groupMap[g.name.lowercase().trim()] = g
        }
        modelGroupCache = groupMap
    }

    /**
     * 选择渠道（核心路由方法）
     * @param model 请求的模型名
     * @param retry 当前重试次数（0=首次，递增=降级到更低优先级层）
     * @param routeMode "url" 或 "model"
     * @return 选中的渠道，或 null（无可用渠道）
     */
    fun selectChannel(model: String, retry: Int, routeMode: String): ChannelEntity? {
        val normalizedModel = model.lowercase().trim()
        val candidates = channelCache[normalizedModel] ?: return null
        if (candidates.isEmpty()) return null

        // 按 priority 分层（降序）
        val priorityGroups = candidates.groupBy { it.priority }
            .toSortedMap(reverseOrder())

        // URL 维度路由：retry 作为优先级层级下标，逐层降级
        // 模型维度路由：同样按优先级分层，但 retry 更快地跨 URL 切换
        val layers = priorityGroups.values.toList()
        val layerIndex = if (routeMode == "url") {
            retry.coerceAtMost(layers.size - 1)
        } else {
            retry.coerceAtMost(layers.size - 1)
        }

        val layer = layers.getOrNull(layerIndex) ?: return null
        if (layer.isEmpty()) return null

        // 层内按 weight 加权随机
        return weightedRandomSelect(layer)
    }

    /** 层内加权随机选择 */
    private fun weightedRandomSelect(channels: List<ChannelEntity>): ChannelEntity {
        val totalWeight = channels.sumOf { it.weight.coerceAtLeast(1) }
        var r = Random.nextInt(totalWeight)
        for (ch in channels) {
            r -= ch.weight.coerceAtLeast(1)
            if (r < 0) return ch
        }
        return channels.last()
    }

    /**
     * 选择 Key（多 Key 轮换）— 线程安全
     * @return 选中的 API Key，或 null（所有 Key 已禁用）
     */
    suspend fun selectKey(channel: ChannelEntity): String? {
        return getLock(channel.id).withLock {
            // v0.7.2: 锁内重读 DB 最新实体，避免用调用方传入的过期快照覆盖并发修改
            // （disableKey 刚写回的禁用状态可能被旧快照回滚）
            val latest = channelRepository.getChannelById(channel.id) ?: channel
            val keys = latest.keyList()
            if (keys.isEmpty()) return@withLock null

            val keyStates = parseKeyStates(latest.keyStates, keys.size)

            when (latest.keyMode) {
                "polling" -> {
                    // 轮询：从 pollingIndex 开始环形找下一个启用 Key
                    var idx = latest.pollingIndex % keys.size
                    for (i in keys.indices) {
                        val checkIdx = (idx + i) % keys.size
                        if (keyStates[checkIdx].enabled) {
                            // 更新轮询索引到下一个位置，并写回当前最新 keyStates（不覆盖并发修改）
                            val nextIndex = (checkIdx + 1) % keys.size
                            channelRepository.updateKeyStates(
                                latest.id, serializeKeyStates(keyStates), nextIndex
                            )
                            return@withLock keys[checkIdx]
                        }
                    }
                    null
                }
                else -> {
                    // random：从启用 Key 中随机选
                    val enabledIndices = keys.indices.filter { keyStates[it].enabled }
                    if (enabledIndices.isEmpty()) return@withLock null
                    keys[enabledIndices.random()]
                }
            }
        }
    }

    /**
     * 禁用某个 Key（AutoBan）
     * @param channel 渠道
     * @param keyIndex 被禁用 Key 的索引
     * @param reason 禁用原因
     */
    suspend fun disableKey(channel: ChannelEntity, keyIndex: Int, reason: String) {
        getLock(channel.id).withLock {
            // v0.7.2: 锁内重读 DB 最新实体（与 selectKey 一致），避免基于过期快照覆盖并发修改
            val latest = channelRepository.getChannelById(channel.id) ?: channel
            val keys = latest.keyList()
            val keyStates = parseKeyStates(latest.keyStates, keys.size).toMutableList()
            if (keyIndex in keyStates.indices) {
                keyStates[keyIndex] = keyStates[keyIndex].copy(
                    enabled = false,
                    disabledReason = reason,
                    disabledTime = System.currentTimeMillis()
                )
            }
            // 检查是否所有 Key 都被禁用
            val allDisabled = keyStates.all { !it.enabled }
            val newStates = serializeKeyStates(keyStates)
            channelRepository.updateKeyStates(latest.id, newStates, latest.pollingIndex)

            if (allDisabled && latest.autoBan) {
                // 所有 Key 都禁用 → 禁用整个渠道
                channelRepository.updateStatus(latest.id, ChannelEntity.STATUS_AUTO_BANNED)
            }
        }
    }

    /**
     * 恢复某个渠道的所有 Key（健康检查通过后调用）
     */
    suspend fun recoverChannel(channel: ChannelEntity) {
        val keyStates = parseKeyStates(channel.keyStates, channel.keyList().size)
        val recovered = keyStates.map { it.copy(enabled = true, disabledReason = null, disabledTime = 0) }
        channelRepository.updateKeyStates(
            channel.id, serializeKeyStates(recovered), 0
        )
        if (channel.status == ChannelEntity.STATUS_AUTO_BANNED) {
            channelRepository.updateStatus(channel.id, ChannelEntity.STATUS_ENABLED)
        }
    }

    /** 获取缓存中所有包含指定模型的渠道 */
    fun getCandidatesForModel(model: String): List<ChannelEntity> {
        return channelCache[model.lowercase().trim()] ?: emptyList()
    }

    /** 获取所有启用渠道 */
    fun getAllEnabledChannels(): List<ChannelEntity> = allEnabledChannels

    // === v0.8.0: 模型组路由 ===

    /**
     * 检查请求的模型名是否匹配某个模型组
     * @return 匹配的 ModelGroupEntity，或 null
     */
    fun findModelGroup(model: String): ModelGroupEntity? {
        return modelGroupCache[model.lowercase().trim()]
    }

    /**
     * 为模型组选择渠道+实际模型名
     * @param model 请求的模型名（即模型组名）
     * @param retry 重试次数（0=第一个成员，1=第二个…）
     * @return Pair(渠道, 实际模型名)，或 null
     */
    fun selectChannelForGroup(model: String, retry: Int): Pair<ChannelEntity, String>? {
        val group = findModelGroup(model) ?: return null
        val members = group.memberList()
        if (members.isEmpty()) return null

        val memberIndex = retry.coerceAtMost(members.size - 1)
        val member = members[memberIndex]

        // 从缓存中查找对应渠道
        val channel = channelByIdCache[member.channelId] ?: return null
        if (channel.status != ChannelEntity.STATUS_ENABLED) return null

        return channel to member.model
    }

    /**
     * 获取模型组的成员数量（用于 RelayHandler 判断重试上限）
     */
    fun getModelGroupMemberCount(model: String): Int {
        return findModelGroup(model)?.memberList()?.size ?: 0
    }

    /**
     * 在 /v1/models 列表中附加模型组名
     */
    fun getModelGroupNames(): Set<String> = modelGroupCache.keys

    // === Key 状态序列化/反序列化 ===

    data class KeyState(
        val index: Int,
        val enabled: Boolean = true,
        val disabledReason: String? = null,
        val disabledTime: Long = 0
    )

    private fun parseKeyStates(json: String, size: Int): List<KeyState> {
        return try {
            val arr = JSONArray(json)
            val result = mutableListOf<KeyState>()
            for (i in 0 until maxOf(arr.length(), size)) {
                if (i < arr.length()) {
                    val obj = arr.getJSONObject(i)
                    result.add(KeyState(
                        index = i,
                        enabled = obj.optBoolean("enabled", true),
                        disabledReason = obj.optString("disabled_reason", null),
                        disabledTime = obj.optLong("disabled_time", 0)
                    ))
                } else {
                    result.add(KeyState(index = i))
                }
            }
            result
        } catch (e: Exception) {
            // 解析失败，默认全部启用
            List(size) { KeyState(index = it) }
        }
    }

    private fun serializeKeyStates(states: List<KeyState>): String {
        val arr = JSONArray()
        for (state in states) {
            val obj = JSONObject()
            obj.put("enabled", state.enabled)
            if (state.disabledReason != null) obj.put("disabled_reason", state.disabledReason)
            if (state.disabledTime > 0) obj.put("disabled_time", state.disabledTime)
            arr.put(obj)
        }
        return arr.toString()
    }
}
