package com.securetalk.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.securetalk.core.database.entity.ChannelEntity
import com.securetalk.core.database.entity.DeviceEntity
import com.securetalk.core.database.entity.IdentityEntity
import com.securetalk.core.database.entity.MemberEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChannelDao {
    @Query("SELECT * FROM channels ORDER BY lastActiveAt DESC")
    fun observeAll(): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels WHERE id = :id")
    suspend fun get(id: String): ChannelEntity?

    @Upsert
    suspend fun upsert(channel: ChannelEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(channels: List<ChannelEntity>)

    @Query("DELETE FROM channels WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE channels SET epoch = :epoch WHERE id = :id")
    suspend fun updateEpoch(id: String, epoch: Long)
}

@Dao
interface MemberDao {
    @Query("SELECT * FROM members WHERE channelId = :channelId")
    fun observeForChannel(channelId: String): Flow<List<MemberEntity>>

    @Query("SELECT * FROM members WHERE channelId = :channelId")
    suspend fun getForChannel(channelId: String): List<MemberEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(members: List<MemberEntity>)

    @Query("DELETE FROM members WHERE channelId = :channelId AND userId = :userId")
    suspend fun delete(channelId: String, userId: String)

    @Query("DELETE FROM members WHERE channelId = :channelId")
    suspend fun deleteAllForChannel(channelId: String)
}

@Dao
interface DeviceDao {
    @Query("SELECT * FROM devices")
    fun observeAll(): Flow<List<DeviceEntity>>

    @Query("SELECT * FROM devices WHERE deviceId = :deviceId")
    suspend fun get(deviceId: String): DeviceEntity?

    @Upsert
    suspend fun upsert(device: DeviceEntity)

    @Query("DELETE FROM devices WHERE deviceId = :deviceId")
    suspend fun delete(deviceId: String)
}

@Dao
interface IdentityDao {
    @Query("SELECT value FROM identity WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Query("SELECT * FROM identity")
    suspend fun getAll(): List<IdentityEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entity: IdentityEntity)
}
