package ua.polodarb.gmsphixit.core.phixit.service

import android.content.Intent
import android.database.sqlite.SQLiteException
import android.os.IBinder
import android.util.Log
import com.google.protobuf.CodedInputStream
import com.google.protobuf.CodedOutputStream
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
import java.io.ByteArrayOutputStream

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
            val safePackage = packageName ?: return
            val safeFlag = flagName ?: return
            Log.i(LOG_TAG, "updateFlag: [$safePackage] $safeFlag = $newValue")

            val flagsMap = dumpFlags(safePackage)
            val modified = flagsMap.mapValues { (_, flags) ->
                flags.map {
                    if (it is BaseFlagModel.BoolFlag && it.name == safeFlag) {
                        Log.d(LOG_TAG, "Modifying flag '${it.name}' to $newValue")
                        it.copy(value = newValue)
                    } else it
                }
            }

            encodeAndSave(safePackage, modified)
        }

        override fun addBoolFlag(packageName: String?, flagName: String?, value: Boolean) {
            Log.i(LOG_TAG, "addBoolFlag() called: package=$packageName, flag=$flagName, value=$value")

            if (packageName.isNullOrBlank() || flagName.isNullOrBlank()) {
                Log.w(LOG_TAG, "Package name or flag name is blank, skipping")
                return
            }

            val flags = dumpFlags(packageName)
            Log.i(LOG_TAG, "Fetched ${flags.size} partitions for $packageName")

            val updated = flags.toMutableMap()

            val targetPartitionId = flags.entries.firstOrNull { (_, list) ->
                list.any { it is BaseFlagModel.BoolFlag }
            }?.key

            if (targetPartitionId == null) {
                Log.w(LOG_TAG, "No partition with bool flags found in $packageName")
                return
            }

            val current = updated[targetPartitionId].orEmpty()
            Log.d(LOG_TAG, "Partition $targetPartitionId has ${current.size} flags")

            if (current.any { it.name == flagName }) {
                Log.i(LOG_TAG, "Flag $flagName already exists, skipping")
                return
            }

            val newFlag = BaseFlagModel.BoolFlag(flagName, value)
            val newList = (current + newFlag).sortedBy { it.name.toLongOrNull() ?: Long.MAX_VALUE }

            updated[targetPartitionId] = newList
            Log.i(LOG_TAG, "Inserting new flag $flagName into partition $targetPartitionId")

            encodeAndSave(packageName, updated)
            Log.i(LOG_TAG, "Flag $flagName successfully inserted and saved")
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

        // ── Step 1: ensure SELinux context for the GMS databases directory ──
        ensureSelinuxAccess()

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
            // Try fixing permissions and retry
            fixDatabasePermissions(dbPath)
            try {
                gmsDatabase = openDatabase(dbPath, null, OPEN_READWRITE)
            } catch (retryExc: Exception) {
                Log.e(LOG_TAG, "Retry also failed: ${retryExc.message}", retryExc)
                throw DatabaseNotFoundException("Cannot open phenotype.db after permission fix: ${e.message}")
            }
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
        // Fallback: search the GMS databases directory for any .db file
        Log.w(LOG_TAG, "No database found at known paths; searching /data/data/com.google.android.gms/databases/")
        val searchResult = Shell.cmd(
            "ls /data/data/com.google.android.gms/databases/*.db 2>/dev/null || echo NO_DB_FILES"
        ).exec()
        val dbs = searchResult.out?.filter { it.endsWith(".db") } ?: emptyList()
        if (dbs.isNotEmpty()) {
            Log.i(LOG_TAG, "Found databases via search: $dbs")
            // Prefer phenotype, else take the first one
            val preferred = dbs.firstOrNull { it.contains("phenotype", ignoreCase = true) }
            return preferred ?: dbs.first()
        }
        return null
    }

    /**
     * On Android 17+, SELinux policies can block the GMS database directory
     * even from a root process. This method attempts to restore the SELinux
     * context on the database file so we can read/write it.
     */
    private fun ensureSelinuxAccess() {
        try {
            val db = "/data/data/com.google.android.gms/databases/phenotype.db"
            // Try base SELinux context first (broader compat), fall back to MLS categories
            Shell.cmd(
                "chcon u:object_r:app_data_file:s0 \"$db\" 2>/dev/null || " +
                "chcon u:object_r:app_data_file:s0:c512,c768 \"$db\" 2>/dev/null || true",
                "chmod 660 \"$db\" 2>/dev/null || true"
            ).exec()
        } catch (e: Exception) {
            Log.w(LOG_TAG, "SELinux context fix failed (non-fatal): ${e.message}")
        }
    }

    /**
     * If the database cannot be opened initially, try fixing permissions via
     * the root shell. This is especially important on Android 17 QPR Beta 4
     * where the GMS database file permissions may be restricted.
     */
    private fun fixDatabasePermissions(dbPath: String) {
        try {
            Shell.cmd(
                "chmod 660 \"$dbPath\"",
                "chown $(stat -c '%u:%g' /data/data/com.google.android.gms/) \"$dbPath\" 2>/dev/null || true",
                // Restore SELinux context to allow the root process to read/write
                "chcon u:object_r:app_data_file:s0 \"$dbPath\" 2>/dev/null || " +
                "chcon u:object_r:app_data_file:s0:c512,c768 \"$dbPath\" 2>/dev/null || true"
            ).exec()
            Log.i(LOG_TAG, "Attempted permission fix for $dbPath")
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Failed to fix permissions: ${e.message}", e)
        }
    }

    // ── Flag dumping / parsing (dynamic queries) ───────────────────

    /**
     * Build a query that returns partition IDs and their flag-content blobs
     * for a given package [packageName], using the dynamically detected schema.
     */
    private fun q(id: String) = "\"$id\""

    private fun buildFlagsQuery(): String {
        val partitionsTable = q(schema.paramPartitionsTable ?: "param_partitions")
        val packagesTable = q(schema.staticConfigPackagesTable ?: "static_config_packages")
        val partitionIdCol = q(schema.partitionIdColumn ?: "param_partition_id")
        val flagsContentCol = q(schema.flagsContentColumn ?: "flags_content")
        val packageIdCol = q(schema.packageIdColumn ?: "static_config_package_id")
        val packageNameCol = q(schema.packageNameColumn ?: "name")

        return """
            SELECT $partitionIdCol, $flagsContentCol
            FROM $partitionsTable
            WHERE $packageIdCol = (
                SELECT $packageIdCol
                FROM $packagesTable
                WHERE $packageNameCol = ?
            );
        """.trimIndent()
    }

    fun dumpFlags(packageName: String): Map<Int, List<BaseFlagModel>> {
        val decodedFlags = mutableMapOf<Int, List<BaseFlagModel>>()
        Log.i(LOG_TAG, "Dumping flags for package: $packageName")

        try {
            val query = buildFlagsQuery()
            val partitionIdCol = schema.partitionIdColumn ?: "param_partition_id"

            gmsDatabase.rawQuery(query, arrayOf(packageName)).use { cursor ->
                val columnIndexId = cursor.getColumnIndexOrThrow(partitionIdCol)
                val columnIndexData = cursor.getColumnIndexOrThrow(
                    schema.flagsContentColumn ?: "flags_content"
                )

                while (cursor.moveToNext()) {
                    val partitionId = cursor.getInt(columnIndexId)
                    val compressedData = cursor.getBlob(columnIndexData)

                    try {
                        val decompressed = ZipUtils.decompress(compressedData)
                        val flags = decodeFlagBlob(decompressed)
                        decodedFlags[partitionId] = flags
                    } catch (e: Exception) {
                        Log.e(LOG_TAG, "Failed to decode partition $partitionId: ${e.message}", e)
                    }
                }
            }

            return decodedFlags
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Error reading flags: ${e.message}", e)
            return emptyMap()
        }
    }

    fun dumpBoolFlags(packageName: String): List<BaseFlagModel.BoolFlag> {
        val boolFlags = mutableListOf<BaseFlagModel.BoolFlag>()
        Log.i(LOG_TAG, "Dumping BOOL flags for package: $packageName")

        try {
            val query = buildFlagsQuery()

            gmsDatabase.rawQuery(query, arrayOf(packageName)).use { cursor ->
                val columnIndexData = cursor.getColumnIndexOrThrow(
                    schema.flagsContentColumn ?: "flags_content"
                )

                while (cursor.moveToNext()) {
                    val compressedData = cursor.getBlob(columnIndexData)

                    try {
                        val decompressed = ZipUtils.decompress(compressedData)
                        decodeBoolFlagsFromBlob(decompressed, boolFlags)
                    } catch (e: Exception) {
                        Log.e(LOG_TAG, "Failed to decode flags: ${e.message}", e)
                    }
                }
            }

            return boolFlags
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Error reading bool flags: ${e.message}", e)
            return emptyList()
        }
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

    /**
     * Decode only boolean flags from a decompressed blob, skipping other types.
     */
    private fun decodeBoolFlagsFromBlob(
        decompressed: ByteArray,
        out: MutableList<BaseFlagModel.BoolFlag>
    ) {
        val input = CodedInputStream.newInstance(decompressed)
        val size = input.readUInt32()
        var next = 0L

        repeat(size) {
            val theory = input.readUInt64()
            val shift = theory ushr 3
            val name = if (shift == 0L) input.readString()
            else (shift + next).also { next = it }.toString()

            when (val type = theory.toInt() and 7) {
                0, 1 -> out.add(BaseFlagModel.BoolFlag(name, type != 0))
                2    -> input.readUInt64()
                3    -> input.readDouble()
                4    -> input.readString()
                5    -> input.readByteArray()
                else -> throw RuntimeException("Unknown flag type: $type")
            }
        }
    }

    // ── flag_overrides persistence (Android 17+ native overrides) ──

    /**
     * Write a flag override to the `flag_overrides` table and stage it for
     * commit in `flag_overrides_to_commit`. This is the **native** persistence
     * mechanism in the Android 17 QPR Beta 4+ GMS schema — GMS natively reads
     * this table and applies overrides, making them persist beyond the 24-hour
     * reset window that afflicts direct param_partitions edits.
     *
     * Schema of flag_overrides (from schema detection):
     *   override_id          INTEGER PRIMARY KEY (auto-increment)
     *   config_package_id    INTEGER REFERENCES config_packages
     *   config_package_name  TEXT
     *   account_id           INTEGER NOT NULL REFERENCES accounts (0 = default)
     *   active               INTEGER DEFAULT 1
     *   name                 TEXT NOT NULL
     *   value                NOT NULL  (the flag value, any type)
     *   type                 INTEGER NOT NULL (0/1=Bool, 2=Int, 3=Float, 4=String, 5=Extension)
     *   source               INTEGER DEFAULT 0
     *   UNIQUE(config_package_id, account_id, active, name)
     *   UNIQUE(config_package_name, account_id, active, name)
     */
    private fun writeFlagOverride(
        packageName: String,
        flagName: String,
        flagValue: BaseFlagModel
    ) {
        val foTable = schema.flagOverridesTable ?: "flag_overrides"
        val focTable = schema.flagOverridesToCommitTable ?: "flag_overrides_to_commit"
        val cpTable = schema.configPackagesTable ?: "config_packages"
        val cpIdCol = schema.configPackageIdColumn ?: "config_package_id"
        val cpNameCol = schema.configPackageNameColumn ?: "name"

        // Find the config_package_id for this package name
        val configPackageId = resolveConfigPackageId(packageName)
        if (configPackageId == null) {
            Log.w(LOG_TAG, "Cannot write flag_overrides: no config_package_id for '$packageName'")
            return
        }

        val accountId = 0  // default account
        val active = 1
        val source = 0
        val typeCode = flagValue.toType()
        val valueStr = when (flagValue) {
            is BaseFlagModel.BoolFlag -> if (flagValue.value) "1" else "0"
            is BaseFlagModel.IntFlag -> flagValue.value.toString()
            is BaseFlagModel.FloatFlag -> flagValue.value.toString()
            is BaseFlagModel.StringFlag -> flagValue.value
            is BaseFlagModel.ExtensionFlag -> "<blob:${flagValue.value.size}>"
        }

        try {
            // DELETE any existing override for this flag + package + account
            // to avoid UNIQUE constraint violation on INSERT
            gmsDatabase.execSQL("""
                DELETE FROM ${q(foTable)}
                WHERE name = ? AND config_package_id = ? AND account_id = ? AND active = ?
            """.trimIndent(), arrayOf(flagName, configPackageId, accountId, active))

            // INSERT fresh override — let SQLite auto-increment override_id
            // NOTE: flag_overrides has CHECK((config_package_id IS NULL) != (config_package_name IS NULL))
            // meaning exactly ONE of the two must be set. We use config_package_id (FK reference)
            // and leave config_package_name NULL.
            gmsDatabase.execSQL("""
                INSERT INTO ${q(foTable)}
                    (config_package_id, config_package_name, account_id, active, name, value, type, source)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(), arrayOf(
                configPackageId,
                null,  // config_package_name must be NULL (XOR constraint with config_package_id)
                accountId,
                active,
                flagName,
                valueStr,
                typeCode.toInt(),
                source
            ))
            Log.i(LOG_TAG, "Written flag_overrides: pkg=$packageName flag=$flagName value=$valueStr type=$typeCode")

            // Stage for commit: get the rowid of the last INSERT (connection-safe)
            val lastId = gmsDatabase.rawQuery(
                "SELECT last_insert_rowid()", null
            ).use { c ->
                if (c.moveToFirst()) c.getLong(0) else -1L
            }

            if (lastId > 0) {
                try {
                    gmsDatabase.execSQL(
                        """INSERT OR IGNORE INTO ${q(focTable)}
                            (override_id, config_package_id, account_id)
                            VALUES (?, ?, ?)""".trimIndent(),
                        arrayOf(lastId, configPackageId, accountId)
                    )
                    Log.i(LOG_TAG, "Staged override $lastId for commit")
                } catch (e: Exception) {
                    Log.w(LOG_TAG, "Failed to stage override in commit table: ${e.message}")
                }
            }

        } catch (e: Exception) {
            Log.e(LOG_TAG, "Failed to write flag_overrides: ${e.message}", e)
        }
    }

    /**
     * Look up the `config_package_id` in the `config_packages` table for a
     * given [packageName] (which is the dotted package name like
     * "com.google.android.gms"). Falls back to trying static_config_packages
     * if config_packages lookup fails.
     */
    private fun resolveConfigPackageId(packageName: String): Long? {
        val cpTable = schema.configPackagesTable ?: "config_packages"
        val cpIdCol = schema.configPackageIdColumn ?: "config_package_id"
        val cpNameCol = schema.configPackageNameColumn ?: "name"

        // Try config_packages first
        try {
            val result = gmsDatabase.rawQuery(
                "SELECT ${q(cpIdCol)} FROM ${q(cpTable)} WHERE ${q(cpNameCol)} = ? LIMIT 1",
                arrayOf(packageName)
            ).use { c ->
                if (c.moveToFirst()) c.getLong(0) else null
            }
            if (result != null) return result
        } catch (e: Exception) {
            Log.w(LOG_TAG, "config_packages lookup failed: ${e.message}")
        }

        // flag_overrides.config_package_id references config_packages.config_package_id,
        // NOT static_config_packages.static_config_package_id — different ID spaces.
        // If config_packages lookup fails, log and skip (don't fall back).
        Log.w(LOG_TAG, "No config_package_id found for '$packageName' in config_packages table")
        return null
    }

    // ── Encoding & saving ───────────────────────────────────────────

    fun encodeAndSave(packageName: String, flags: Map<Int, List<BaseFlagModel>>) {
        try {
            Log.i(LOG_TAG, "Encoding flags for $packageName")

            val partitionsTable = schema.paramPartitionsTable ?: "param_partitions"
            val partitionIdCol = schema.partitionIdColumn ?: "param_partition_id"
            val flagsContentCol = schema.flagsContentColumn ?: "flags_content"

            val encoded = flags.map { (id, flagList) ->
                val out = ByteArrayOutputStream()
                val cos = CodedOutputStream.newInstance(out)
                var next = 0L

                cos.writeUInt32NoTag(flagList.size)
                Log.d(LOG_TAG, "Partition $id - flags count: ${flagList.size}")

                flagList.forEach { f ->
                    try {
                        f.name.toLongOrNull()?.let { ln ->
                            val theory = ((ln - next) shl 3) or f.toType()
                            cos.writeUInt64NoTag(theory)
                            next = ln
                            Log.d(LOG_TAG, "[OFFSET] name=$ln theory=$theory type=${f.toType()}")
                        } ?: run {
                            cos.writeUInt64NoTag(f.toType())
                            cos.writeStringNoTag(f.name)
                            Log.d(LOG_TAG, "[STRING] name=${f.name} type=${f.toType()}")
                        }

                        when (f) {
                            is BaseFlagModel.BoolFlag -> {}
                            is BaseFlagModel.IntFlag -> cos.writeUInt64NoTag(f.value)
                            is BaseFlagModel.FloatFlag -> cos.writeDoubleNoTag(java.lang.Double.longBitsToDouble(f.value))
                            is BaseFlagModel.StringFlag -> cos.writeStringNoTag(f.value)
                            is BaseFlagModel.ExtensionFlag -> cos.writeByteArrayNoTag(f.value)
                        }
                    } catch (e: Exception) {
                        Log.e(LOG_TAG, "Failed to encode flag '${f.name}': ${e.message}", e)
                    }
                }

                cos.flush()
                id to ZipUtils.compress(out.toByteArray())
            }

            encoded.forEach { (id, blob) ->
                try {
                    val query = """
                        UPDATE ${q(partitionsTable)}
                        SET ${q(flagsContentCol)} = ?
                        WHERE ${q(partitionIdCol)} = ?
                    """.trimIndent()

                    gmsDatabase.execSQL(query, arrayOf(blob, id))
                    Log.i(LOG_TAG, "Updated partition $id with ${blob.size} bytes")
                } catch (e: Exception) {
                    Log.e(LOG_TAG, "Failed to update partition $id: ${e.message}", e)
                }
            }

            // Update serving version in the detected last_fetch table
            updateServingVersion()

            // Also write flag_overrides for persistent storage (Android 17+)
            // Only writes bool flags for now — other types can be added later
            if (schema.flagOverridesTable != null) {
                val boolFlags = flags.values.flatten().filterIsInstance<BaseFlagModel.BoolFlag>()
                for (flag in boolFlags) {
                    writeFlagOverride(packageName, flag.name, flag)
                }
            }

            try {
                clearPhenotypeCache(packageName)
                Log.i(LOG_TAG, "Cleared phenotype cache for $packageName")
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Failed to clear phenotype cache: ${e.message}", e)
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Fatal error in encodeAndSave: ${e.message}", e)
        }
    }

    /**
     * Update the serving version timestamp in the last-fetch (or equivalent) table
     * to prevent GMS from immediately reverting our changes.
     */
    private fun updateServingVersion() {
        val lastFetchTable = schema.lastFetchTable ?: "last_fetch"
        val servingVersion = (System.currentTimeMillis() / 1000).toInt()

        // Try with the standard 'type = 1' WHERE clause first
        try {
            gmsDatabase.execSQL(
                "UPDATE ${q(lastFetchTable)} SET serving_version = ? WHERE type = 1",
                arrayOf(servingVersion)
            )
            Log.i(LOG_TAG, "Updated serving version in $lastFetchTable to $servingVersion")
            return
        } catch (_: Exception) {
            Log.w(LOG_TAG, "Failed to update serving version with 'type=1'; trying without WHERE")
        }

        // Fallback: try updating any row
        try {
            val columns = mutableListOf<String>()
            gmsDatabase.rawQuery("PRAGMA table_info(\"$lastFetchTable\")", null).use { c ->
                val nameIdx = c.getColumnIndexOrThrow("name")
                while (c.moveToNext()) columns.add(c.getString(nameIdx))
            }
            if ("serving_version" in columns) {
                gmsDatabase.execSQL(
                    "UPDATE ${q(lastFetchTable)} SET serving_version = ?",
                    arrayOf(servingVersion)
                )
                Log.i(LOG_TAG, "Updated serving version (no WHERE clause) in $lastFetchTable")
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Serving version update failed (non-fatal): ${e.message}")
        }
    }

    fun getAllConfigPackagesInternal(): List<String> {
        val packages = mutableListOf<String>()
        try {
            val packagesTable = schema.staticConfigPackagesTable ?: "static_config_packages"
            val nameCol = schema.packageNameColumn ?: "name"
            val query = "SELECT ${q(nameCol)} FROM ${q(packagesTable)};"

            gmsDatabase.rawQuery(query, null).use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow(nameCol)
                while (cursor.moveToNext()) {
                    packages.add(cursor.getString(nameIndex))
                }
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Error reading config packages: ${e.message}", e)
        }
        Log.i(LOG_TAG, "Found ${packages.size} config packages, first 10: ${packages.take(10)}")
        return packages
    }

    fun clearPhenotypeCache(androidPkgName: String) {
        Log.i(LOG_TAG, "Clearing phenotype cache for $androidPkgName")

        // Force-stop the target app
        Shell.cmd("am force-stop $androidPkgName").exec()

        // Remove phenotype cache files — try two common locations
        Shell.cmd("rm -rf /data/data/$androidPkgName/files/phenotype").exec()
        Shell.cmd("rm -rf /data/data/$androidPkgName/cache/phenotype").exec()

        // App-specific cache cleanups
        if (androidPkgName.contains("finsky") || androidPkgName.contains("vending")) {
            Shell.cmd("rm -rf /data/data/com.android.vending/files/experiment*").exec()
            Shell.cmd("am force-stop com.android.vending").exec()
        }
        if (androidPkgName.contains("com.google.android.apps.photos")) {
            Shell.cmd("rm -rf /data/data/com.google.android.apps.photos/shared_prefs/phenotype*").exec()
            Shell.cmd("rm -rf /data/data/com.google.android.apps.photos/shared_prefs/com.google.android.apps.photos.phenotype.xml").exec()
            Shell.cmd("am force-stop com.google.android.apps.photos").exec()
        }

        // Harder force-stop cycle to ensure GMS picks up changes
        repeat(3) {
            Shell.cmd("am start -a android.intent.action.MAIN -n $androidPkgName &").exec()
            Shell.cmd("am force-stop $androidPkgName").exec()
        }
    }

    companion object {
        private const val LOG_TAG = "PhixitFlagsService"
    }
}

class DatabaseNotFoundException(message: String) : Exception(message)
