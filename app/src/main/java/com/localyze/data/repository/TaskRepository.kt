package com.localyze.data.repository

import com.localyze.data.local.TaskDao
import com.localyze.domain.models.Task
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

interface TaskRepository {
    fun getAllTasks(): Flow<List<Task>>
    fun getPendingTasks(): Flow<List<Task>>
    fun getCompletedTasks(): Flow<List<Task>>
    suspend fun getAllTasksList(): List<Task>
    suspend fun getTaskById(id: Long): Task?
    suspend fun saveTask(task: Task): Task
    suspend fun updateTask(task: Task)
    suspend fun deleteTask(id: Long)
    suspend fun updateCompletionStatus(id: Long, isCompleted: Boolean)
    suspend fun getPendingTaskCount(): Int
}

@Singleton
class TaskRepositoryImpl @Inject constructor(
    private val taskDao: TaskDao
) : TaskRepository {

    override fun getAllTasks(): Flow<List<Task>> {
        return taskDao.getAllTasks()
    }

    override fun getPendingTasks(): Flow<List<Task>> {
        return taskDao.getPendingTasks()
    }

    override fun getCompletedTasks(): Flow<List<Task>> {
        return taskDao.getCompletedTasks()
    }

    override suspend fun getAllTasksList(): List<Task> {
        return taskDao.getAllTasksList()
    }

    override suspend fun getTaskById(id: Long): Task? {
        return taskDao.getTaskById(id)
    }

    override suspend fun saveTask(task: Task): Task {
        val id = taskDao.insert(task)
        return task.copy(id = id)
    }

    override suspend fun updateTask(task: Task) {
        taskDao.update(task)
    }

    override suspend fun deleteTask(id: Long) {
        taskDao.deleteById(id)
    }

    override suspend fun updateCompletionStatus(id: Long, isCompleted: Boolean) {
        taskDao.updateCompletionStatus(id, isCompleted)
    }

    override suspend fun getPendingTaskCount(): Int {
        return taskDao.getPendingTaskCount()
    }
}
