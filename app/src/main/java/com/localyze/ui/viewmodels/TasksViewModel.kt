package com.localyze.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localyze.data.repository.TaskRepository
import com.localyze.domain.models.Task
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TasksUiState(
    val tasks: List<Task> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val showAddDialog: Boolean = false,
    val showEditDialog: Boolean = false,
    val selectedTask: Task? = null,
    val searchQuery: String = "",
    val filter: TaskFilter = TaskFilter.ALL
)

enum class TaskFilter {
    ALL, PENDING, COMPLETED
}

@HiltViewModel
class TasksViewModel @Inject constructor(
    private val taskRepository: TaskRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(TasksUiState())
    val uiState: StateFlow<TasksUiState> = _uiState.asStateFlow()

    init {
        loadTasks()
    }

    fun loadTasks() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                taskRepository.getAllTasks().collect { tasks ->
                    _uiState.update { it.copy(tasks = tasks, isLoading = false) }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message, isLoading = false) }
            }
        }
    }

    fun createTask(title: String, description: String = "", dueDate: Long? = null) {
        if (title.isBlank()) return
        viewModelScope.launch {
            try {
                val task = Task(
                    title = title.trim(),
                    description = description.trim(),
                    dueDate = dueDate,
                    isCompleted = false
                )
                taskRepository.saveTask(task)
                _uiState.update { it.copy(showAddDialog = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    fun updateTask(task: Task) {
        viewModelScope.launch {
            try {
                taskRepository.updateTask(task)
                _uiState.update { it.copy(showEditDialog = false, selectedTask = null) }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    fun deleteTask(taskId: Long) {
        viewModelScope.launch {
            try {
                taskRepository.deleteTask(taskId)
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    fun toggleTaskCompletion(task: Task) {
        viewModelScope.launch {
            try {
                taskRepository.updateCompletionStatus(task.id, !task.isCompleted)
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    fun showAddDialog() {
        _uiState.update { it.copy(showAddDialog = true) }
    }

    fun dismissAddDialog() {
        _uiState.update { it.copy(showAddDialog = false) }
    }

    fun showEditDialog(task: Task) {
        _uiState.update { it.copy(showEditDialog = true, selectedTask = task) }
    }

    fun dismissEditDialog() {
        _uiState.update { it.copy(showEditDialog = false, selectedTask = null) }
    }

    fun updateSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun setFilter(filter: TaskFilter) {
        _uiState.update { it.copy(filter = filter) }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun getFilteredTasks(): List<Task> {
        val state = _uiState.value
        var filtered = state.tasks

        // Apply filter
        filtered = when (state.filter) {
            TaskFilter.PENDING -> filtered.filter { !it.isCompleted }
            TaskFilter.COMPLETED -> filtered.filter { it.isCompleted }
            TaskFilter.ALL -> filtered
        }

        // Apply search
        if (state.searchQuery.isNotBlank()) {
            val query = state.searchQuery.lowercase()
            filtered = filtered.filter {
                it.title.lowercase().contains(query) ||
                it.description.lowercase().contains(query)
            }
        }

        return filtered
    }
}
