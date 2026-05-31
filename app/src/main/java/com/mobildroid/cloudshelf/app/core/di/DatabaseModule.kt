package com.mobildroid.cloudshelf.app.core.di

import android.content.Context
import androidx.room.Room
import com.mobildroid.cloudshelf.app.data.db.CloudShelfDatabase
import com.mobildroid.cloudshelf.app.data.db.transfer.TransferDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): CloudShelfDatabase =
        Room.databaseBuilder(context, CloudShelfDatabase::class.java, CloudShelfDatabase.NAME)
            // Phase 3 starts at schema v1 — no migrations needed yet.
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideTransferDao(db: CloudShelfDatabase): TransferDao = db.transferDao()
}
