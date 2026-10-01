package com.example.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.model.ClonePreset
import com.example.model.CloneRecord
import kotlinx.coroutines.flow.Flow

@Dao
interface ClonePresetDao {
    @Query("SELECT * FROM clone_presets ORDER BY createdAt DESC")
    fun getAllPresets(): Flow<List<ClonePreset>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPreset(preset: ClonePreset): Long

    @Query("DELETE FROM clone_presets WHERE id = :id")
    suspend fun deletePreset(id: Long)

    @Query("SELECT * FROM clone_presets WHERE id = :id LIMIT 1")
    suspend fun getPreset(id: Long): ClonePreset?
}

@Dao
interface CloneRecordDao {
    @Query("SELECT * FROM clone_records ORDER BY createdAt DESC")
    fun getAllClones(): Flow<List<CloneRecord>>

    @Query("SELECT * FROM clone_records WHERE id = :id LIMIT 1")
    fun getCloneById(id: Long): Flow<CloneRecord?>

    @Query("SELECT * FROM clone_records WHERE sourcePackage = :sourcePackage")
    fun getClonesForSource(sourcePackage: String): Flow<List<CloneRecord>>

    @Query("SELECT * FROM clone_records WHERE clonePackageId = :packageId LIMIT 1")
    suspend fun getCloneByPackageId(packageId: String): CloneRecord?

    @Query("SELECT COUNT(*) FROM clone_records")
    fun getCloneCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertClone(record: CloneRecord): Long

    @Update
    suspend fun updateClone(record: CloneRecord)

    @Query("UPDATE clone_records SET installStatus = :status WHERE clonePackageId = :packageId")
    suspend fun updateInstallStatus(packageId: String, status: String)

    @Delete
    suspend fun deleteClone(record: CloneRecord)

    @Query("DELETE FROM clone_records WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM clone_records")
    suspend fun clearAll()
}
