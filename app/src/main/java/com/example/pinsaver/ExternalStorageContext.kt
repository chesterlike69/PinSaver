package com.example.pinsaver

import android.content.Context
import android.content.ContextWrapper
import java.io.File

/**
 * Перехватывает getDatabasePath(), чтобы Room создавал базу
 * в /sdcard/Android/data/<pkg>/files/databases/
 * Доступно без root через USB/ADB.
 */
class ExternalStorageContext(base: Context) : ContextWrapper(base) {

    override fun getDatabasePath(name: String): File {
        val baseDir = getExternalFilesDir(null) ?: filesDir
        val dir = File(baseDir, "databases")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, name)
    }
}