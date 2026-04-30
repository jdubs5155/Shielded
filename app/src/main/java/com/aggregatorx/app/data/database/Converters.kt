package com.aggregatorx.app.data.database

import androidx.room.TypeConverter
import com.aggregatorx.app.data.model.PaginationType

class Converters {
    @TypeConverter
    fun fromPaginationType(value: PaginationType): String = value.name

    @TypeConverter
    fun toPaginationType(value: String): PaginationType = PaginationType.valueOf(value)
}
