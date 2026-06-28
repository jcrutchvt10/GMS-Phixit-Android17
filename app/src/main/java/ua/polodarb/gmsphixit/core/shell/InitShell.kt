package ua.polodarb.gmsphixit.core.shell

import android.util.Log
import com.topjohnwu.superuser.Shell

object InitShell {

    private const val SHELL_TIMEOUT = 15L

    private val shellConfig = Shell.Builder.create()
        .setFlags(Shell.FLAG_REDIRECT_STDERR or Shell.FLAG_MOUNT_MASTER)
        .setTimeout(SHELL_TIMEOUT)

    fun initShell() {
        try {
            Shell.setDefaultBuilder(shellConfig)
            Log.i(LOG_TAG, "Default shell builder configured")
            verifyRootAccess()
        } catch (_: IllegalStateException) {
            Log.d(LOG_TAG, "Shell already initialized")
        }
    }

    private fun verifyRootAccess() {
        try {
            val shell = Shell.getShell()
            val isRoot = shell.isRoot
            Log.i(LOG_TAG, "Shell root access: $isRoot")

            if (!isRoot) {
                Log.w(LOG_TAG, "No root access detected!")
                return
            }

            val suVersionResult = Shell.cmd("su -V 2>/dev/null || echo unknown").exec()
            Log.i(LOG_TAG, "su version: ${suVersionResult.out?.joinToString("")?.trim()}")

            val selinuxResult = Shell.cmd("getenforce 2>/dev/null || echo unknown").exec()
            Log.i(LOG_TAG, "SELinux status: ${selinuxResult.out?.joinToString("")?.trim()}")

            val dbPath = "/data/data/com.google.android.gms/databases"
            val dbCheck = Shell.cmd(
                "ls -la \"$dbPath/phenotype.db\" 2>/dev/null || echo NO_DB_FILE"
            ).exec()
            val dbOutput = dbCheck.out?.joinToString("")?.trim()
            Log.i(LOG_TAG, "Database accessibility: $dbOutput")

            if (dbOutput != null && !dbOutput.contains("NO_DB_FILE")) {
                val dbFile = "$dbPath/phenotype.db"
                // Try base context first, fall back to MLS categories
                Shell.cmd(
                    "chcon u:object_r:app_data_file:s0 \"$dbFile\" 2>/dev/null || " +
                    "chcon u:object_r:app_data_file:s0:c512,c768 \"$dbFile\" 2>/dev/null || true"
                ).exec()
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Root verification failed (non-fatal): ${e.message}")
        }
    }

    private const val LOG_TAG = "InitShell"
}