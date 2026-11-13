package com.securetalk.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.securetalk.core.database.dao.ChannelDao
import com.securetalk.core.database.dao.DeviceDao
import com.securetalk.core.database.dao.IdentityDao
import com.securetalk.core.database.dao.MemberDao
import com.securetalk.core.database.entity.ChannelEntity
import com.securetalk.core.database.entity.DeviceEntity
import com.securetalk.core.database.entity.IdentityEntity
import com.securetalk.core.database.entity.MemberEntity

@Database(
    entities = [
        ChannelEntity::class,
        MemberEntity::class,
        DeviceEntity::class,
        IdentityEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class SecureTalkDatabase : RoomDatabase() {
    abstract fun channelDao(): ChannelDao
    abstract fun memberDao(): MemberDao
    abstract fun deviceDao(): DeviceDao
    abstract fun identityDao(): IdentityDao

    companion object {
        @Volatile
        private var instance: SecureTalkDatabase? = null

        fun get(context: Context): SecureTalkDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    SecureTalkDatabase::class.java,
                    "securetalk.db",
                ).fallbackToDestructiveMigration().build().also { instance = it }
            }
    }
}
