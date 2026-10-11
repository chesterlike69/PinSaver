package com.example.pinsaver

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [Pin::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun pinDao(): PinDao
}