package com.drmohammed.antiporn.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.drmohammed.antiporn.data.local.db.entity.PartnerEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for accountability partners (Guardians and Wards).
 */
@Dao
interface PartnerDao {

    @Query("SELECT * FROM partners ORDER BY created_at DESC")
    fun getAllPartnersFlow(): Flow<List<PartnerEntity>>

    @Query("SELECT * FROM partners ORDER BY created_at DESC")
    suspend fun getAllPartners(): List<PartnerEntity>

    @Query("SELECT * FROM partners ORDER BY created_at DESC")
    fun getAllPartnersBlocking(): List<PartnerEntity>

    @Query("SELECT * FROM partners WHERE role = :role ORDER BY created_at DESC")
    fun getPartnersByRoleFlow(role: String): Flow<List<PartnerEntity>>

    @Query("SELECT * FROM partners WHERE role = :role ORDER BY created_at DESC")
    suspend fun getPartnersByRole(role: String): List<PartnerEntity>

    @Query("SELECT * FROM partners WHERE role = :role ORDER BY created_at DESC")
    fun getPartnersByRoleBlocking(role: String): List<PartnerEntity>

    @Query("SELECT * FROM partners WHERE id = :id LIMIT 1")
    suspend fun getPartnerById(id: String): PartnerEntity?

    @Query("SELECT * FROM partners WHERE id = :id LIMIT 1")
    fun getPartnerByIdBlocking(id: String): PartnerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun savePartner(partner: PartnerEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun savePartnerBlocking(partner: PartnerEntity)

    @Query("DELETE FROM partners WHERE id = :id")
    suspend fun deletePartner(id: String): Int

    @Query("DELETE FROM partners WHERE id = :id")
    fun deletePartnerBlocking(id: String): Int

    @Query("UPDATE partners SET display_name = :newName, last_sync_at = :syncTimestamp WHERE id = :id")
    suspend fun updatePartnerDisplayName(id: String, newName: String, syncTimestamp: Long): Int

    @Query("UPDATE partners SET display_name = :newName, last_sync_at = :syncTimestamp WHERE id = :id")
    fun updatePartnerDisplayNameBlocking(id: String, newName: String, syncTimestamp: Long): Int
}
