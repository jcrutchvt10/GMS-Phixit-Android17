package ua.polodarb.gmsphixit.core.phixit

import android.util.Log
import io.requery.android.database.sqlite.SQLiteDatabase

/**
 * Result of inspecting the GMS phenotype database schema at runtime.
 * Instead of hardcoding table/column names, we discover them from sqlite_master
 * and adapt our queries accordingly. This makes the app resilient to schema
 * changes introduced in Android 17 QPR Beta 4+.
 */
data class DetectedSchema(
    /** The raw user_version pragma from the database. */
    val userVersion: Int,
    /** Which known schema family the database matches (or UNKNOWN). */
    val family: SchemaFamily,
    /** The table used in place of `param_partitions` (detected dynamically). */
    val paramPartitionsTable: String?,
    /** The table used in place of `static_config_packages` (detected dynamically). */
    val staticConfigPackagesTable: String?,
    /** The table used in place of `last_fetch` (detected dynamically). */
    val lastFetchTable: String?,
    /** All tables in the database, for debugging / fallback. */
    val allTables: List<String>,
    /** Columns for the param-partitions-like table, keyed by detected name. */
    val paramPartitionsColumns: List<String> = emptyList(),
    /** Columns for the static-config-packages-like table. */
    val staticConfigPackagesColumns: List<String> = emptyList(),
    /** Name of the column holding the partition id (e.g., param_partition_id). */
    val partitionIdColumn: String? = null,
    /** Name of the column holding the flags blob (e.g., flags_content). */
    val flagsContentColumn: String? = null,
    /** Name of the column holding the package reference (e.g., static_config_package_id). */
    val packageIdColumn: String? = null,
    /** Primary key in the package table, which may differ from the partition foreign key. */
    val staticPackageIdColumn: String? = null,
    /** Name of the package name column (e.g., `name` in static_config_packages). */
    val packageNameColumn: String? = null,
    // ── flag_overrides persistence tables (Android 17 QPR Beta 4+) ──
    /** The `flag_overrides` table for persistent per-account flag overrides. */
    val flagOverridesTable: String? = null,
    /** The `flag_overrides_to_commit` staging table. */
    val flagOverridesToCommitTable: String? = null,
    /** The `config_packages` table linking static config to Android packages. */
    val configPackagesTable: String? = null,
    /** The `accounts` table for account-based overrides. */
    val accountsTable: String? = null,
    /** Columns for the config_packages table. */
    val configPackagesColumns: List<String> = emptyList(),
    /** Name of the config_package_id column. */
    val configPackageIdColumn: String? = null,
    /** Name of the config_package_name column. */
    val configPackageNameColumn: String? = null,
    /** Name of the override_id column. */
    val overrideIdColumn: String? = null,
    /** Name of the active column in flag_overrides. */
    val overrideActiveColumn: String? = null,
    /** Name of the source column in flag_overrides. */
    val overrideSourceColumn: String? = null,
)

enum class SchemaFamily {
    /** The original GMS Flags schema (pre-Phixit). */
    LEGACY,
    /** The Phixit schema (tables: param_partitions, static_config_packages). */
    PHIXIT,
    /** The newer schema introduced in Android 17 QPR Beta 4 (table names may differ). */
    ANDROID_17,
    /** No recognised pattern – we will try to infer the structure. */
    UNKNOWN
}

object SchemaDetector {

    private const val TAG = "SchemaDetector"

    /**
     * Inspect [db] and return a [DetectedSchema] that describes what we found.
     * This is called once right after the database is opened so that all
     * subsequent queries can be built dynamically.
     */
    fun detect(db: SQLiteDatabase): DetectedSchema {
        val version = try {
            db.version
        } catch (e: Exception) {
            Log.w(TAG, "Could not read user_version", e)
            -1
        }
        Log.i(TAG, "Database user_version = $version")

        val allTables = listTables(db)
        Log.i(TAG, "Found ${allTables.size} tables: $allTables")

        val family = classify(version, allTables)
        Log.i(TAG, "Classified as schema family: $family")

        // Try to locate the three key tables by trying known names
        val paramPartitionsTable = resolveTable(allTables, listOf(
            "param_partitions",
            "param_partitions_v2",
            "flags_partitions",
            "flag_partitions",
            "config_partitions",
            "partition_flags"
        ))
        val staticConfigPackagesTable = resolveTable(allTables, listOf(
            "static_config_packages",
            "config_packages",
            "flag_packages",
            "package_configs",
            "app_configs"
        ))
        val lastFetchTable = resolveTable(allTables, listOf(
            "last_fetch",
            "last_sync",
            "fetch_log",
            "sync_state"
        ))

        val paramPartitionsColumns = paramPartitionsTable?.let { getColumns(db, it) } ?: emptyList()
        val staticConfigPackagesColumns = staticConfigPackagesTable?.let { getColumns(db, it) } ?: emptyList()

        val partitionIdColumn = fuzzyMatchColumn(paramPartitionsColumns, listOf(
            "param_partition_id", "partition_id", "config_id", "id", "flag_group_id"
        ))
        val flagsContentColumn = fuzzyMatchColumn(paramPartitionsColumns, listOf(
            "flags_content", "flag_data", "config_data", "payload", "content", "data", "blob"
        ))
        val packageIdColumn = fuzzyMatchColumn(paramPartitionsColumns, listOf(
            "static_config_package_id", "package_id", "app_id", "config_package_id"
        ))
        val packageNameColumn = fuzzyMatchColumn(staticConfigPackagesColumns, listOf(
            "name", "package_name", "app_name", "config_name"
        ))

        // ── flag_overrides persistence tables ──
        val flagOverridesTable = resolveTable(allTables, listOf(
            "flag_overrides", "flag_override", "overrides", "flag_override_table"
        ))
        val flagOverridesToCommitTable = resolveTable(allTables, listOf(
            "flag_overrides_to_commit", "overrides_to_commit"
        ))
        val configPackagesTable = resolveTable(allTables, listOf(
            "config_packages", "config_package"
        ))
        val accountsTable = resolveTable(allTables, listOf(
            "accounts", "accounts_table"
        ))

        val flagOverridesColumns = flagOverridesTable?.let { getColumns(db, it) } ?: emptyList()
        val configPackagesColumns = configPackagesTable?.let { getColumns(db, it) } ?: emptyList()

        // Detect flag_overrides column names
        val overrideIdColumn = fuzzyMatchColumn(flagOverridesColumns, listOf(
            "override_id", "id", "flag_override_id"
        ))
        val overrideActiveColumn = fuzzyMatchColumn(flagOverridesColumns, listOf(
            "active", "is_active", "enabled"
        ))
        val overrideSourceColumn = fuzzyMatchColumn(flagOverridesColumns, listOf(
            "source", "override_source", "source_type"
        ))
        val configPackageIdColumn = fuzzyMatchColumn(configPackagesColumns, listOf(
            "config_package_id", "id"
        ))
        val configPackageNameColumn = fuzzyMatchColumn(configPackagesColumns, listOf(
            "name", "package_name", "config_name"
        ))

        return DetectedSchema(
            userVersion = version,
            family = family,
            paramPartitionsTable = paramPartitionsTable,
            staticConfigPackagesTable = staticConfigPackagesTable,
            lastFetchTable = lastFetchTable,
            allTables = allTables,
            paramPartitionsColumns = paramPartitionsColumns,
            staticConfigPackagesColumns = staticConfigPackagesColumns,
            partitionIdColumn = partitionIdColumn,
            flagsContentColumn = flagsContentColumn,
            packageIdColumn = packageIdColumn,
            staticPackageIdColumn = fuzzyMatchColumn(staticConfigPackagesColumns, listOf(
                packageIdColumn ?: "", "static_config_package_id", "config_package_id", "package_id", "id"
            )),
            packageNameColumn = packageNameColumn,
            flagOverridesTable = flagOverridesTable,
            flagOverridesToCommitTable = flagOverridesToCommitTable,
            configPackagesTable = configPackagesTable,
            accountsTable = accountsTable,
            configPackagesColumns = configPackagesColumns,
            configPackageIdColumn = configPackageIdColumn,
            configPackageNameColumn = configPackageNameColumn,
            overrideIdColumn = overrideIdColumn,
            overrideActiveColumn = overrideActiveColumn,
            overrideSourceColumn = overrideSourceColumn
        )
    }

    // ── private helpers ──────────────────────────────────────────

    private fun listTables(db: SQLiteDatabase): List<String> {
        val tables = mutableListOf<String>()
        try {
            db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name",
                null
            ).use { c ->
                while (c.moveToNext()) tables.add(c.getString(0))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to list tables", e)
        }
        return tables
    }

    private fun getColumns(db: SQLiteDatabase, table: String): List<String> {
        val cols = mutableListOf<String>()
        try {
            db.rawQuery("PRAGMA table_info(\"$table\")", null).use { c ->
                val nameIdx = c.getColumnIndexOrThrow("name")
                while (c.moveToNext()) cols.add(c.getString(nameIdx))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get columns for $table", e)
        }
        return cols
    }

    /**
     * Return the first [candidates] entry that exists in [allTables], or null.
     */
    private fun resolveTable(allTables: List<String>, candidates: List<String>): String? {
        val lower = allTables.map { it.lowercase() }
        for (c in candidates) {
            val idx = lower.indexOf(c.lowercase())
            if (idx >= 0) return allTables[idx]
        }
        return null
    }

    /**
     * Return the first [candidates] entry that exists in [columns], or null.
     */
    private fun fuzzyMatchColumn(columns: List<String>, candidates: List<String>): String? {
        val lower = columns.map { it.lowercase() }
        for (c in candidates) {
            val idx = lower.indexOf(c.lowercase())
            if (idx >= 0) return columns[idx]
        }
        return null
    }

    /**
     * Classify the schema family based on user_version and known table names.
     */
    private fun classify(version: Int, tables: List<String>): SchemaFamily {
        val lowerTables = tables.map { it.lowercase() }

        // Android 17 QPR Beta 4+ may have new table names — check for those first
        val android17Indicators = listOf(
            "flag_partitions", "config_partitions",
            "flag_packages", "app_configs",
            "partition_flags"
        )
        if (lowerTables.any { it in android17Indicators }) {
            return SchemaFamily.ANDROID_17
        }

        // Phixit schema
        if ("param_partitions" in lowerTables && "static_config_packages" in lowerTables) {
            return SchemaFamily.PHIXIT
        }

        // Legacy GMS Flags schema
        if (version > 0 && version < 1033) {
            return SchemaFamily.LEGACY
        }

        return SchemaFamily.UNKNOWN
    }
}
