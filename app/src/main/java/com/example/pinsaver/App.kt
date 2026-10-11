package com.example.pinsaver

import android.app.Application
import android.util.Log
import androidx.room.Room
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

class App : Application() {

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val dbContext by lazy { ExternalStorageContext(this) }
    private val lock = Any()

    val dbGeneration = MutableStateFlow(0)

    private var _database: AppDatabase? = null
    private var _repository: PinRepository? = null

    val database: AppDatabase
        get() = synchronized(lock) {
            _database ?: run {
                Log.d("PinSaver", "opening DB at ${dbContext.getDatabasePath("pins.db")}")
                Room.databaseBuilder(
                    dbContext,
                    AppDatabase::class.java,
                    "pins.db"
                ).build().also { _database = it }
            }
        }

    val repository: PinRepository
        get() = synchronized(lock) {
            _repository ?: PinRepository(database.pinDao()).also { _repository = it }
        }

    override fun onCreate() {
        super.onCreate()
        Log.d("PinSaver", "App.onCreate: start")
        // Никакой миграции в onCreate — сделаем её лениво при первом обращении.
        Log.d("PinSaver", "App.onCreate: done")
    }

    fun dbFile(): File = dbContext.getDatabasePath("pins.db")

    fun closeDatabase() {
        synchronized(lock) {
            runCatching { _database?.close() }
            _database = null
            _repository = null
        }
    }

    fun reopenDatabase() {
        synchronized(lock) {
            database
            repository
            dbGeneration.value += 1
        }
    }
}