package ua.polodarb.gmsphixit.core.phixit

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import com.topjohnwu.superuser.ipc.RootService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import ua.polodarb.gmsphixit.core.phixit.service.IPhixitFlagsService
import ua.polodarb.gmsphixit.core.phixit.service.PhixitFlagsService
import javax.inject.Inject

data class DatabaseInitializationState(
    val isInitialized: Boolean,
    val error: String? = null
)

class InitRootDB @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val _databaseInitializationStateFlow =
        MutableStateFlow(DatabaseInitializationState(false))
    val databaseInitializationStateFlow: Flow<DatabaseInitializationState> =
        _databaseInitializationStateFlow

    private var rootDatabase: IPhixitFlagsService? = null
    private var retryCount = 0
    private val maxRetries = 3
    private val retryDelayMs = 1_000L

    fun setDatabaseInitialized(isInitialized: Boolean, error: String? = null) {
        _databaseInitializationStateFlow.value = DatabaseInitializationState(
            isInitialized = isInitialized,
            error = error
        )
    }

    fun initDB() {
        Log.d(LOG_TAG, "initDB called (attempt ${retryCount + 1}/$maxRetries)")
        val intent = Intent(context, PhixitFlagsService::class.java)
        val service = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                Log.d(LOG_TAG, "onServiceConnected: $name, binder=$binder")
                try {
                    rootDatabase = IPhixitFlagsService.Stub.asInterface(binder)
                    Log.d(LOG_TAG, "rootDatabase assigned: $rootDatabase")
                    retryCount = 0
                    setDatabaseInitialized(true)
                    Log.d(LOG_TAG, "setDatabaseInitialized(true)")
                } catch (e: Exception) {
                    Log.e(LOG_TAG, "Error connecting to database", e)
                    setDatabaseInitialized(false, "Error connecting: ${e.message}")
                    scheduleRetry()
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                Log.d(LOG_TAG, "onServiceDisconnected: $name")
                rootDatabase = null
                setDatabaseInitialized(false, "Service disconnected")
                scheduleRetry()
            }

            override fun onBindingDied(name: ComponentName?) {
                Log.e(LOG_TAG, "onBindingDied: $name")
                rootDatabase = null
                setDatabaseInitialized(false, "Binding died")
                scheduleRetry()
            }

            override fun onNullBinding(name: ComponentName?) {
                Log.e(LOG_TAG, "onNullBinding: $name")
                setDatabaseInitialized(false, "Null binding")
                scheduleRetry()
            }
        }

        try {
            RootService.bind(intent, service)
            Log.d(LOG_TAG, "RootService.bind called")
        } catch (e: SecurityException) {
            Log.e(LOG_TAG, "RootService.bind failed with SecurityException", e)
            setDatabaseInitialized(false, "SecurityException: ${e.message}")
        } catch (e: Exception) {
            Log.e(LOG_TAG, "RootService.bind failed", e)
            setDatabaseInitialized(false, "Bind failed: ${e.message}")
        }
    }

    private fun scheduleRetry() {
        if (retryCount >= maxRetries) {
            Log.e(LOG_TAG, "Max retries ($maxRetries) reached. Giving up.")
            setDatabaseInitialized(false, "Max retries reached. Check root access.")
            return
        }
        retryCount++
        Log.i(LOG_TAG, "Scheduling retry $retryCount/$maxRetries in ${retryDelayMs}ms")

        Thread {
            SystemClock.sleep(retryDelayMs)
            initDB()
        }.start()
    }

    fun getRootDatabase(): IPhixitFlagsService {
        return checkNotNull(rootDatabase) {
            "RootDatabase is not initialized. Please check root access and try again."
        }
    }

    suspend fun getDbVersion(): Int {
        return try {
            getRootDatabase().getDbVersion()
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Failed to get DB version", e)
            -1
        }
    }

    companion object {
        private const val LOG_TAG = "InitRootDB"
    }
}