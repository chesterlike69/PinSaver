package com.example.pinsaver

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "pins")
data class Pin(
    @PrimaryKey val url: String,
    val title: String?,
    val author: String?,
    val thumbnailUrl: String?,
    val thumbWidth: Int,
    val thumbHeight: Int,
    val savedAt: Long
)