package com.llmrouter.data.repo

import com.llmrouter.data.db.ChannelDao
import com.llmrouter.data.model.ChannelEntity
import kotlinx.coroutines.flow.Flow

class ChannelRepository(private val dao: ChannelDao) {

    fun getAllChannels(): Flow<List<ChannelEntity>> = dao.getAllChannels()

    suspend fun getEnabledChannels(): List<ChannelEntity> = dao.getEnabledChannels()

    suspend fun getChannelById(id: Long): ChannelEntity? = dao.getChannelById(id)

    suspend fun insert(channel: ChannelEntity): Long = dao.insert(channel)

    suspend fun update(channel: ChannelEntity) = dao.update(channel)

    suspend fun delete(channel: ChannelEntity) = dao.delete(channel)

    suspend fun updateStatus(id: Long, status: Int) = dao.updateStatus(id, status)

    suspend fun updateTestResult(id: Long, responseTime: Int, testTime: Long) =
        dao.updateTestResult(id, responseTime, testTime)

    suspend fun updateKeyStates(id: Long, keyStates: String, pollingIndex: Int) =
        dao.updateKeyStates(id, keyStates, pollingIndex)

    suspend fun incrementQuota(id: Long) = dao.incrementQuota(id)
}
