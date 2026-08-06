package com.llmrouter.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.llmrouter.data.model.ChannelEntity
import com.llmrouter.data.model.ModelGroupEntity
import com.llmrouter.data.model.RouteLogEntity

@Database(
    entities = [ChannelEntity::class, RouteLogEntity::class, ModelGroupEntity::class],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun channelDao(): ChannelDao
    abstract fun routeLogDao(): RouteLogDao
    abstract fun modelGroupDao(): ModelGroupDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "llm_router.db"
                ).fallbackToDestructiveMigration().build().also {
                    INSTANCE = it
                }
            }
        }
    }
}
