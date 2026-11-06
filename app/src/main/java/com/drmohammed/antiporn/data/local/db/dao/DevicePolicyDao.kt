package com.drmohammed.antiporn.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.drmohammed.antiporn.data.local.db.entity.DevicePolicyEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for device policies.
 */
@Dao
interface DevicePolicyDao {

    @Query("SELECT * FROM device_policies WHERE id = 1 LIMIT 1")
    fun getDevicePolicyFlow(): Flow<DevicePolicyEntity?>

    @Query("SELECT * FROM device_policies WHERE id = 1 LIMIT 1")
    suspend fun getDevicePolicy(): DevicePolicyEntity?

    @Query("SELECT * FROM device_policies WHERE id = 1 LIMIT 1")
    fun getDevicePolicyBlocking(): DevicePolicyEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveDevicePolicy(entity: DevicePolicyEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun saveDevicePolicyBlocking(entity: DevicePolicyEntity)
}
