package com.llmrouter.data.repo

import com.llmrouter.data.db.ModelGroupDao
import com.llmrouter.data.model.ModelGroupEntity
import com.llmrouter.data.model.ModelGroupMember
import kotlinx.coroutines.flow.Flow

class ModelGroupRepository(private val dao: ModelGroupDao) {

    fun getAllGroups(): Flow<List<ModelGroupEntity>> = dao.getAllGroups()

    suspend fun getAllGroupsOnce(): List<ModelGroupEntity> = dao.getAllGroupsOnce()

    suspend fun getGroupById(id: Long): ModelGroupEntity? = dao.getGroupById(id)

    suspend fun insert(group: ModelGroupEntity): Long = dao.insert(group)

    suspend fun update(group: ModelGroupEntity) = dao.update(group)

    suspend fun delete(group: ModelGroupEntity) = dao.delete(group)

    suspend fun deleteById(id: Long) = dao.deleteById(id)

    /**
     * 便捷方法：创建模型组（自动序列化成员列表）
     */
    suspend fun createGroup(name: String, displayName: String, members: List<ModelGroupMember>): Long {
        val entity = ModelGroupEntity(
            name = name.trim(),
            displayName = displayName.trim(),
            members = ModelGroupEntity.serializeMembers(members)
        )
        return dao.insert(entity)
    }

    /**
     * 便捷方法：更新模型组成员
     */
    suspend fun updateMembers(id: Long, members: List<ModelGroupMember>) {
        val group = dao.getGroupById(id) ?: return
        dao.update(group.copy(members = ModelGroupEntity.serializeMembers(members)))
    }
}
