package com.mobildroid.cloudshelf.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.mobildroid.cloudshelf.app.data.db.transfer.TransferDao
import com.mobildroid.cloudshelf.app.data.db.transfer.TransferEntity

@Database(
    entities = [TransferEntity::class],
    version = 1,
    exportSchema = false
)
abstract class CloudShelfDatabase : RoomDatabase() {
    abstract fun transferDao(): TransferDao

    companion object {
        const val NAME = "cloudshelf_db"
    }
}
