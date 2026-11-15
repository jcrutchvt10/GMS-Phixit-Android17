package ua.polodarb.gmsphixit.core.phixit.service

import android.content.Intent
import android.database.sqlite.SQLiteException
import android.os.IBinder
import android.util.Log
import com.google.protobuf.CodedInputStream
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ipc.RootService
import io.requery.android.database.sqlite.SQLiteDatabase
import io.requery.android.database.sqlite.SQLiteDatabase.OPEN_READWRITE
import io.requery.android.database.sqlite.SQLiteDatabase.openDatabase
import ua.polodarb.gmsphixit.core.phixit.SchemaDetector
import ua.polodarb.gmsphixit.core.phixit.DetectedSchema
import ua.polodarb.gmsphixit.core.phixit.model.BaseFlagModel
import ua.polodarb.gmsphixit.core.phixit.model.ParcelableFlagModel
import ua.polodarb.gmsphixit.core.phixit.model.toParcelable
import ua.polodarb.gmsphixit.utils.ZipUtils

class PhixitFlagsService : RootService() {

    private lateinit var gmsDatabase: SQLiteDatabase
    private lateinit var schema: DetectedSchema

    /** Ordered list of candidate database paths to try. */
    private val dbPathCandidates = listOf(
        // Standard legacy + current path
        "/data/data/com.google.android.gms/databases/phenotype.db",
        // Alternative GMS databases directory
        "/data/data/com.google.android.gms/databases/phenotype/phenotype.db",
        // Some GMS variants store it under a different name
        "/data/data/com.google.android.gms/databases/gms_phenotype.db",
        "/data/data/com.google.android.gms/databases/ph_config.db",
        // Direct data directory (when symlinked)
        "/data/data/com.google.android.gms/databases/phenotype/phenotype.db",
    )

    override fun onCreate() {
        super.onCreate()
        Log.i(LOG_TAG, "PhixitFlagsService onCreate")
    }

    val binder = object : IPhixitFlagsService.Stub() {

        override fun getAllFlags(packageName: String?): List<ParcelableFlagModel> {
            val safeName = packageName ?: ""
            Log.i(LOG_TAG, "getAllFlags called for: $safeName")
            return dumpFlags(safeName).values.flatten().map { it.toParcelable() }
        }

        override fun getBoolFlags(packageName: String?): List<ParcelableFlagModel> {
            val safeName = packageName ?: ""
            Log.i(LOG_TAG, "getBoolFlags called for: $safeName")
            return dumpBoolFlags(safeName).map { it.toParcelable() }
        }

        override fun updateFlag(packageName: String?, flagName: String?, newValue: Boolean) {
            stageBoolOverride(requireNotNull(packageName), requireNotNull(flagName), newValue, false)
        }

        override fun addBoolFlag(packageName: String?, flagName: String?, value: Boolean) {
            stageBoolOverride(requireNotNull(packageName), requireNotNull(flagName), value, true)
        }

        override fun getAllConfigPackages(): List<String> {
            return getAllConfigPackagesInternal()
        }

        override fun getDbVersion(): Int {
            return try {
                if (::schema.isInitialized) schema.userVersion else gmsDatabase.version
            } catch (e: Exception) {
                -1
            }
        }
    }

    override fun onBind(intent: Intent): IBinder {
        Log.i(LOG_TAG, "PhixitFlagsService onBind called")

        // ── Step 2: discover the actual database path ──
        val dbPath = discoverDatabasePath()
        if (dbPath == null) {
            Log.e(LOG_TAG, "Could not find phenotype database at any known path")
            throw DatabaseNotFoundException(
                "phenotype.db not found. Tried: ${dbPathCandidates.joinToString(", ")}"
            )
        }
        Log.i(LOG_TAG, "Using database path: $dbPath")

        // ── Step 3: open the database ──
        try {
            gmsDatabase = openDatabase(dbPath, null, OPEN_READWRITE)
        } catch (e: SQLiteException) {
            Log.e(LOG_TAG, "Database not found or cannot be opened: ${e.message}", e)
            throw DatabaseNotFoundException("Cannot open phenotype database: ${e.message}")
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Unexpected error in onBind: ${e.message}", e)
            throw DatabaseNotFoundException("Unexpected error opening database: ${e.message}")
        }

        // ── Step 4: detect schema ──
        try {
            schema = SchemaDetector.detect(gmsDatabase)
            Log.i(LOG_TAG, "Detected schema: family=${schema.family}, " +
                    "paramPartitionsTable=${schema.paramPartitionsTable}, " +
                    "staticConfigPackagesTable=${schema.staticConfigPackagesTable}, " +
                    "lastFetchTable=${schema.lastFetchTable}")
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Schema detection failed; will use fallback queries", e)
            // Create a minimal fallback schema that uses the hardcoded names
            schema = DetectedSchema(
                userVersion = gmsDatabase.version,
                family = ua.polodarb.gmsphixit.core.phixit.SchemaFamily.UNKNOWN,
                paramPartitionsTable = "param_partitions",
                staticConfigPackagesTable = "static_config_packages",
                lastFetchTable = "last_fetch",
                allTables = emptyList(),
                partitionIdColumn = "param_partition_id",
                flagsContentColumn = "flags_content",
                packageIdColumn = "static_config_package_id",
                staticPackageIdColumn = "static_config_package_id",
                packageNameColumn = "name"
            )
        }

        return binder
    }

    override fun onUnbind(intent: Intent): Boolean {
        Log.i(LOG_TAG, "PhixitFlagsService onUnbind called")
        if (::gmsDatabase.isInitialized && gmsDatabase.isOpen) gmsDatabase.close()
        return super.onUnbind(intent)
    }

    override fun onRebind(intent: Intent) {
        if (::gmsDatabase.isInitialized && gmsDatabase.isOpen) gmsDatabase.close()
        super.onRebind(intent)
    }

    override fun onDestroy() {
        if (::gmsDatabase.isInitialized && gmsDatabase.isOpen) gmsDatabase.close()
        super.onDestroy()
    }

    // ── Database discovery & permission helpers ─────────────────────

    /**
     * Try each candidate path, using a root shell to check for the file's existence.
     * Returns the first path for which `test -f` succeeds, or `null` if none found.
     */
    private fun discoverDatabasePath(): String? {
        for (path in dbPathCandidates) {
            val result = Shell.cmd("test -f \"$path\" && echo EXISTS || echo NOT_FOUND").exec()
            val output = result.out?.joinToString("")?.trim()
            if (output == "EXISTS") {
                Log.d(LOG_TAG, "Found database at: $path")
                return path
            }
        }
        return null
    }

    // Read the partitions and overrides linked to the selected committed state.
    private fun requireSupportedSchema() {
        val required = listOf("config_packages", "experiment_states", "experiment_states_to_partitions",
            "param_partitions", "experiment_states_to_overrides", "flag_overrides", "flag_overrides_to_commit")
        check(required.all { requiredTable -> schema.allTables.any { it.equals(requiredTable, true) } }) {
            "This Phenotype database schema is not supported. No flags were changed."
        }
    }

    private data class SelectedConfig(val id: Long, val stateId: Long, val accountId: Long)

    private fun selectedConfig(name: String): SelectedConfig {
        requireSupportedSchema()
        return gmsDatabase.rawQuery("""
            SELECT c.config_package_id, e.experiment_state_id, e.account_id
            FROM config_packages c JOIN experiment_states e
              ON e.experiment_state_id = c.committed_experiment_state_id
             AND e.config_package_id = c.config_package_id
            WHERE c.name = ?
        """.trimIndent(), arrayOf(name)).use { c ->
            check(c.moveToFirst()) { "No committed configuration found for this package." }
            SelectedConfig(c.getLong(0), c.getLong(1), c.getLong(2))
        }
    }

    private fun readCommittedFlags(config: SelectedConfig): List<BaseFlagModel> {
        val flags = linkedMapOf<String, BaseFlagModel>()
        gmsDatabase.rawQuery("""
            SELECT p.flags_content FROM experiment_states_to_partitions ep
            JOIN param_partitions p ON p.param_partition_id = ep.param_partition_id
            JOIN config_packages c ON c.config_package_id = ?
              AND p.static_config_package_id = c.static_config_package_id
            WHERE ep.experiment_state_id = ? ORDER BY ep.ordinal, p.param_partition_id
        """.trimIndent(), arrayOf(config.id.toString(), config.stateId.toString())).use { cursor ->
            while (cursor.moveToNext()) {
                for (flag in decodeFlagBlob(ZipUtils.decompress(cursor.getBlob(0)))) {
                    check(!flags.containsKey(flag.name)) { "Duplicate flag in committed partitions; precedence is unknown." }
                    flags[flag.name] = flag
                }
            }
        }
        // Only overrides linked to this state are known to have been committed.
        gmsDatabase.rawQuery("""
            SELECT f.name, f.type, f.value FROM experiment_states_to_overrides eo
            JOIN flag_overrides f ON f.override_id = eo.override_id
            WHERE eo.experiment_state_id = ?
        """.trimIndent(), arrayOf(config.stateId.toString())).use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(0)
                val type = cursor.getInt(1)
                val value = cursor.getString(2)
                check(type == 2 && value in listOf("0", "1")) {
                    "Unsupported committed override type; cannot safely display this configuration."
                }
                flags[name] = BaseFlagModel.BoolFlag(name, value == "1")
            }
        }
        return flags.values.toList()
    }

    fun dumpFlags(packageName: String): Map<Int, List<BaseFlagModel>> =
        mapOf(0 to readCommittedFlags(selectedConfig(packageName)))

    fun dumpBoolFlags(packageName: String): List<BaseFlagModel.BoolFlag> =
        readCommittedFlags(selectedConfig(packageName)).filterIsInstance<BaseFlagModel.BoolFlag>()

    /** Ask Play services to apply and commit the override, then verify the committed state. */
    private fun stageBoolOverride(packageName: String, flagName: String, value: Boolean, adding: Boolean) {
        require(packageName.isNotBlank() && flagName.isNotBlank()) { "Package and flag name are required." }
        require(!flagName.contains(',')) { "Flag names containing commas are not supported by the override receiver." }
        val config = selectedConfig(packageName)
        val existingFlag = readCommittedFlags(config).find { it.name == flagName }
        if (adding) check(existingFlag == null) { "A committed flag with this name already exists." }
        else check(existingFlag is BaseFlagModel.BoolFlag) { "The committed boolean flag was not found." }
        // Play services requires wildcard scope for immediate commits. It applies the
        // override to every account for the selected configuration.
        fun quote(s: String) = "'" + s.replace("'", "'\\''") + "'"
        val command = "am broadcast -a com.google.android.gms.phenotype.FLAG_OVERRIDE " +
            "-p com.google.android.gms --es package ${quote(packageName)} --es user ${quote("*")} " +
            "--ez commit true --esa flags ${quote(flagName)} --esa values ${if (value) "true" else "false"} --esa types boolean"
        val result = Shell.cmd(command).exec()
        check(result.isSuccess) { "Could not send the override to Google Play services." }
        // An Android broadcast result does not report whether the asynchronous operation succeeded.
        repeat(10) {
            Thread.sleep(500)
            val committed = gmsDatabase.rawQuery("""
                SELECT 1 FROM config_packages c
                JOIN experiment_states_to_overrides eo ON eo.experiment_state_id = c.committed_experiment_state_id
                JOIN flag_overrides f ON f.override_id = eo.override_id
                WHERE c.config_package_id = ? AND f.name = ? AND f.type = 2 AND f.value = ?
                  AND f.active = 1 AND f.account_id = (SELECT account_id FROM accounts WHERE name = '*') LIMIT 1
            """.trimIndent(), arrayOf(config.id.toString(), flagName, if (value) "1" else "0")).use { it.moveToFirst() }
            if (committed) return
        }
        throw IllegalStateException("Google Play services did not confirm the override. No change is shown as committed.")
    }

    /**
     * Decode a raw decompressed blob into a list of [BaseFlagModel].
     *
     * This uses the standard Phenotype protobuf encoding:
     *   - varint: count of flags
     *   - for each flag:
     *     - varint: (type & value) — lower 3 bits = type, rest = offset/name
     *     - [optional] string: flag name (if offset == 0)
     *     - value: depends on type (0/1=Bool, 2=varint, 3=double, 4=string, 5=bytes)
     */
    private fun decodeFlagBlob(decompressed: ByteArray): List<BaseFlagModel> {
        val input = CodedInputStream.newInstance(decompressed)
        val size = input.readUInt32()
        var next = 0L

        return List(size) {
            val theory = input.readUInt64()
            val shift = theory ushr 3
            val name = if (shift == 0L) input.readString()
            else (shift + next).also { next = it }.toString()

            when (val type = theory.toInt() and 7) {
                0, 1 -> BaseFlagModel.BoolFlag(name, type != 0)
                2    -> BaseFlagModel.IntFlag(name, input.readUInt64())
                3    -> BaseFlagModel.FloatFlag(name, java.lang.Double.doubleToRawLongBits(input.readDouble()))
                4    -> BaseFlagModel.StringFlag(name, input.readString())
                5    -> BaseFlagModel.ExtensionFlag(name, input.readByteArray())
                else -> throw RuntimeException("Unknown flag type: $type")
            }
        }
    }

    fun getAllConfigPackagesInternal(): List<String> {
        requireSupportedSchema()
        val packages = mutableListOf<String>()
        gmsDatabase.rawQuery("""
            SELECT c.name FROM config_packages c JOIN experiment_states e
            ON e.experiment_state_id = c.committed_experiment_state_id
            AND e.config_package_id = c.config_package_id ORDER BY c.name
        """.trimIndent(), null).use { cursor ->
            while (cursor.moveToNext()) packages.add(cursor.getString(0))
        }
        return packages
    }

    companion object {
        private const val LOG_TAG = "PhixitFlagsService"
    }
}

class DatabaseNotFoundException(message: String) : Exception(message)
