package com.example.pinsaver

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PinDao {

    @Query("SELECT * FROM pins ORDER BY savedAt DESC")
    fun observeAll(): Flow<List<Pin>>

    @Query("SELECT COUNT(*) FROM pins")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(pin: Pin)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(pins: List<Pin>)
}