package com.localyze.di

import android.content.Context
import com.localyze.data.local.AppDatabase
import com.localyze.data.local.AttachmentMemoryDao
import com.localyze.data.local.ConversationDao
import com.localyze.data.local.MemoryDao
import com.localyze.data.local.MessageDao
import com.localyze.data.local.ReplyDraftDao
import com.localyze.data.local.TaskDao
import com.localyze.data.local.ToolAuditDao
import com.localyze.data.repository.ChatRepository
import com.localyze.data.repository.ChatRepositoryImpl
import com.localyze.data.repository.MemoryRepository
import com.localyze.data.repository.MemoryRepositoryImpl
import com.localyze.data.repository.TaskRepository
import com.localyze.data.repository.TaskRepositoryImpl
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
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        return AppDatabase.getInstance(context)
    }

    @Provides
    fun provideMessageDao(database: AppDatabase): MessageDao = database.messageDao()

    @Provides
    fun provideConversationDao(database: AppDatabase): ConversationDao = database.conversationDao()

    @Provides
    fun provideMemoryDao(database: AppDatabase): MemoryDao = database.memoryDao()

    @Provides
    fun provideTaskDao(database: AppDatabase): TaskDao = database.taskDao()

    @Provides
    fun provideAttachmentMemoryDao(database: AppDatabase): AttachmentMemoryDao = database.attachmentMemoryDao()

    @Provides
    fun provideToolAuditDao(database: AppDatabase): ToolAuditDao = database.toolAuditDao()

    @Provides
    fun provideReplyDraftDao(database: AppDatabase): ReplyDraftDao = database.replyDraftDao()

    @Provides
    @Singleton
    fun provideChatRepository(impl: ChatRepositoryImpl): ChatRepository = impl

    @Provides
    @Singleton
    fun provideMemoryRepository(impl: MemoryRepositoryImpl): MemoryRepository = impl

    @Provides
    @Singleton
    fun provideTaskRepository(impl: TaskRepositoryImpl): TaskRepository = impl
}
