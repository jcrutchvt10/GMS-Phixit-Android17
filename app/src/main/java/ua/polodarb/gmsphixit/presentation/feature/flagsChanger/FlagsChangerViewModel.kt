package ua.polodarb.gmsphixit.presentation.feature.flagsChanger

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds
import ua.polodarb.gmsphixit.core.phixit.InitRootDB
import ua.polodarb.gmsphixit.core.phixit.model.ParcelableFlagModel
import ua.polodarb.gmsphixit.core.phixit.model.ParcelableBoolFlag
import javax.inject.Inject

@HiltViewModel
class FlagsChangerViewModel @Inject constructor(
    application: Application,
    private val rootDB: InitRootDB
) : AndroidViewModel(application) {

    var state by mutableStateOf(FlagsChangerState())
        private set

    private var currentPackageName: String = ""
    private var searchJob: kotlinx.coroutines.Job? = null

    fun loadFlags(packageName: String) {
        currentPackageName = packageName
        state = state.copy(isLoading = true, error = null)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val phixitService = rootDB.getRootDatabase()
                val flags = phixitService.getBoolFlags(packageName).distinctBy { it.name }
                launch(Dispatchers.Main) {
                    state = state.copy(
                        isLoading = false,
                        flags = flags,
                        filteredFlags = flags,
                        packageName = packageName
                    )
                    filterFlags()
                }
            } catch (e: Exception) {
                launch(Dispatchers.Main) {
                    state = state.copy(
                        isLoading = false,
                        error = e.message ?: "Failed to load flags"
                    )
                }
            }
        }
    }

    fun onSearch(query: String) {
        state = state.copy(searchQuery = query)
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(300.milliseconds)
            filterFlags()
        }
    }

    private fun filterFlags() {
        val query = state.searchQuery.trim().lowercase()
        val filtered = if (query.isBlank()) {
            state.flags
        } else {
            state.flags.filter { it.name.contains(query, ignoreCase = true) }
        }
        val sorted = filtered.sortedWith(compareByDescending<ParcelableFlagModel> {
            it.name.toIntOrNull()
        }.thenBy {
            it.name.lowercase()
        })
        state = state.copy(filteredFlags = sorted)
    }

    fun updateFlag(flagName: String, newValue: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                rootDB.getRootDatabase().updateFlag(currentPackageName, flagName, newValue)
                launch(Dispatchers.Main) {
                    loadFlags(currentPackageName)
                }
            } catch (e: Exception) {
                launch(Dispatchers.Main) {
                    state = state.copy(error = "Failed to commit flag: ${e.message}")
                }
            }
        }
    }

    fun addFlag(flagName: String, flagValue: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                rootDB.getRootDatabase().addBoolFlag(currentPackageName, flagName, flagValue)
                launch(Dispatchers.Main) {
                    state = state.copy(showAddDialog = false, newFlagName = "", newFlagValue = false)
                    loadFlags(currentPackageName)
                }
            } catch (e: Exception) {
                launch(Dispatchers.Main) { state = state.copy(error = "Failed to commit flag: ${e.message}") }
            }
        }
    }

    fun showAddDialog() {
        state = state.copy(showAddDialog = true)
    }

    fun hideAddDialog() {
        state = state.copy(showAddDialog = false)
    }

    fun updateNewFlagName(name: String) {
        state = state.copy(newFlagName = name)
    }

    fun updateNewFlagValue(value: Boolean) {
        state = state.copy(newFlagValue = value)
    }

    fun clearError() {
        state = state.copy(error = null)
    }
}

data class FlagsChangerState(
    val isLoading: Boolean = false,
    val flags: List<ParcelableFlagModel> = emptyList(),
    val filteredFlags: List<ParcelableFlagModel> = emptyList(),
    val packageName: String = "",
    val searchQuery: String = "",
    val error: String? = null,
    val showAddDialog: Boolean = false,
    val newFlagName: String = "",
    val newFlagValue: Boolean = false
)
