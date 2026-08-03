package com.llmrouter.data.db

import androidx.room.*
import com.llmrouter.data.model.ChannelEntity
import com.llmrouter.data.model.RouteLogEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChannelDao {

    @Query("SELECT * FROM channels ORDER BY priority DESC, createdAt ASC")
    fun getAllChannels(): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels WHERE status = 1 ORDER BY priority DESC, createdAt ASC")
    suspend fun getEnabledChannels(): List<ChannelEntity>

    @Query("SELECT * FROM channels WHERE id = :id")
    suspend fun getChannelById(id: Long): ChannelEntity?

    @Insert
    suspend fun insert(channel: ChannelEntity): Long

    @Update
    suspend fun update(channel: ChannelEntity)

    @Delete
    suspend fun delete(channel: ChannelEntity)

    @Query("UPDATE channels SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: Int)

    @Query("UPDATE channels SET responseTime = :responseTime, testTime = :testTime WHERE id = :id")
    suspend fun updateTestResult(id: Long, responseTime: Int, testTime: Long)

    @Query("UPDATE channels SET keyStates = :keyStates, pollingIndex = :pollingIndex WHERE id = :id")
    suspend fun updateKeyStates(id: Long, keyStates: String, pollingIndex: Int)

    @Query("UPDATE channels SET usedQuota = usedQuota + 1 WHERE id = :id")
    suspend fun incrementQuota(id: Long)
}

@Dao
interface RouteLogDao {

    @Query("SELECT * FROM route_logs ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentLogs(limit: Int = 100): Flow<List<RouteLogEntity>>

    @Insert
    suspend fun insert(log: RouteLogEntity)

    @Query("SELECT COUNT(*) FROM route_logs WHERE success = 1")
    fun getSuccessCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM route_logs")
    fun getTotalCount(): Flow<Int>

    @Query("SELECT AVG(responseTime) FROM route_logs WHERE success = 1")
    fun getAvgResponseTime(): Flow<Float?>

    @Query("DELETE FROM route_logs WHERE timestamp < :before")
    suspend fun cleanOldLogs(before: Long)
}
