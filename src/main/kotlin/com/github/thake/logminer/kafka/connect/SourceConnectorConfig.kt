package com.github.thake.logminer.kafka.connect

import mu.KotlinLogging
import org.apache.kafka.common.config.AbstractConfig
import org.apache.kafka.common.config.ConfigDef
import org.apache.kafka.common.config.ConfigDef.Importance
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.time.Duration
import java.time.ZoneId

private val logger = KotlinLogging.logger {}

sealed class LogMinerSelector
data class TableSelector(val owner: String, val tableName: String) : LogMinerSelector()
data class SchemaSelector(val owner: String) : LogMinerSelector()
enum class LogminerDictionarySource {
    ONLINE, REDO_LOG
}

class SourceConnectorConfig(
    config: ConfigDef?,
    parsedConfig: Map<String, String>
) : AbstractConfig(config, parsedConfig) {
    constructor(parsedConfig: Map<String, String>) : this(
        conf(),
        parsedConfig
    )

    fun openConnection() : Connection? {
        val dbUri = "${dbHostName}:${dbPort}:${dbSid}"
        fun doOpenConnection(): Connection {
            return DriverManager.getConnection(
                "jdbc:oracle:thin:@$dbUri",
                dbUser, dbPassword
            ).also {
                logger.info { "Connected to database at $dbUri" }
            }
        }

        var currentAttempt = 0
        var connection: Connection? = null
        while (currentAttempt < dbAttempts && connection == null) {
            if (currentAttempt > 0) {
                logger.info { "Waiting ${dbBackoff.toMillis()} ms before next attempt to acquire a connection" }
                Thread.sleep(dbBackoff.toMillis())
            }
            currentAttempt++
            try {
                connection = doOpenConnection()
            } catch (e: SQLException) {
                logger.error(e) { "Couldn't connect to database with url $dbUri. Attempt $currentAttempt." }

            }
        }
        return connection
    }


    val dbSid: String
        get() = getString(DB_SID)

    val dbHostName: String
        get() = getString(DB_HOST)

    val dbPort: Int
        get() = getInt(DB_PORT)

    val dbUser: String
        get() = getString(DB_USERNAME)

    val dbPassword: String
        get() = getString(DB_PASSWORD)

    val dbName: String
        get() = getString(DB_NAME)

    val dbZoneId: ZoneId
        get() = ZoneId.of(getString(DB_TIMEZONE))


    val logminerDictionarySource: LogminerDictionarySource
        get() = LogminerDictionarySource.valueOf(getString(DB_LOGMINER_DICTIONARY))

    val monitoredTables: List<String>
        get() = getString(MONITORED_TABLES).split(",").map { it.trim() }

    val logMinerSelectors: List<LogMinerSelector>
        get() = monitoredTables.map {
            val parts = it.split(".")
            if (parts.size > 1) {
                TableSelector(parts[0], parts[1])
            } else {
                SchemaSelector(parts[0])
            }
        }

    val batchSize: Int
        get() = getInt(BATCH_SIZE)
    val dbFetchSize: Int
        get() = getInt(DB_FETCH_SIZE) ?: batchSize


    val startScn: Long
        get() = getLong(START_SCN) ?: 0


    val pollInterval: Duration
        get() = Duration.ofMillis(getLong(POLL_INTERVAL_MS))

    val dbBackoff: Duration
        get() = Duration.ofMillis(getLong(DB_BACKOFF_MS))

    val dbAttempts: Int
        get() = getInt(DB_ATTEMPTS)

    val isTombstonesOnDelete : Boolean
        get() = getBoolean(TOMBSTONES_ON_DELETE)

    companion object {
        const val DB_NAME = "db.name"
        const val DB_SID = "db.sid"
        const val DB_HOST = "db.hostname"
        const val DB_PORT = "db.port"
        const val DB_USERNAME = "db.user"
        const val DB_PASSWORD = "db.user.password"
        const val DB_ATTEMPTS = "db.attempts"
        const val DB_BACKOFF_MS = "db.backoff.ms"
        const val DB_LOGMINER_DICTIONARY = "db.logminer.dictionary"
        const val DB_TIMEZONE = "db.timezone"
        const val MONITORED_TABLES = "table.whitelist"
        const val DB_FETCH_SIZE = "db.fetch.size"
        const val START_SCN = "start.scn"
        const val BATCH_SIZE = "batch.size"
        const val POLL_INTERVAL_MS = "poll.interval.ms"
        const val TOMBSTONES_ON_DELETE = "tombstones.on.delete"

        fun conf(): ConfigDef {
            return ConfigDef()
                .define(
                    DB_NAME,
                    ConfigDef.Type.STRING,
                    Importance.HIGH,
                    "Logical name of the database. This name will be used as a prefix for the topic. You can choose this name as you like."
                )
                .define(
                    DB_SID,
                    ConfigDef.Type.STRING,
                    Importance.HIGH,
                    "Database SID"
                )
                .define(
                    DB_HOST,
                    ConfigDef.Type.STRING,
                    Importance.HIGH,
                    "Database hostname"
                )
                .define(
                    DB_PORT,
                    ConfigDef.Type.INT,
                    Importance.HIGH,
                    "Database port (usually 1521)"
                )
                .define(
                    DB_USERNAME,
                    ConfigDef.Type.STRING,
                    Importance.HIGH,
                    "Database user"
                )
                .define(
                    DB_PASSWORD,
                    ConfigDef.Type.STRING,
                    Importance.HIGH,
                    "Database password"
                )
                .define(
                    DB_LOGMINER_DICTIONARY,
                    ConfigDef.Type.STRING,
                    LogminerDictionarySource.ONLINE.name,
                    Importance.LOW,
                    "Type of logminer dictionary that should be used. Valid values: " + LogminerDictionarySource.values()
                        .joinToString { it.name }
                )
                .define(
                    DB_TIMEZONE,
                    ConfigDef.Type.STRING,
                    "UTC",
                    Importance.HIGH,
                    "The timezone in which TIMESTAMP columns (without any timezone information) should be interpreted as. Valid values are all values that can be passed to https://docs.oracle.com/javase/8/docs/api/java/time/ZoneId.html#of-java.lang.String-"
                )
                .define(
                    MONITORED_TABLES,
                    ConfigDef.Type.STRING,
                    "",
                    Importance.HIGH,
                    "Tables that should be monitored, separated by ','. Tables have to be specified with schema. Table names are case-sensitive (e.g. if your table name is an unquoted identifier, you'll need to specify it in all caps). You can also just " +
                            "specify a schema to indicate that all tables within that schema should be monitored. Examples: 'MY_USER.TABLE, OTHER_SCHEMA'."
                )
                .define(
                    TOMBSTONES_ON_DELETE,
                    ConfigDef.Type.BOOLEAN,
                    true,
                    Importance.HIGH,
                    "If set to false, no tombstone records will be emitted after a delete operation."
                )
                .define(
                    BATCH_SIZE,
                    ConfigDef.Type.INT,
                    1000,
                    Importance.HIGH,
                    "Batch size of rows that should be fetched in one batch"
                )
                .define(
                    DB_FETCH_SIZE,
                    ConfigDef.Type.INT,
                    null,
                    Importance.MEDIUM,
                    "JDBC result set prefetch size. If not set, it will be defaulted to batch.size. The fetch" +
                            " should not be smaller than the batch size."
                )
                .define(
                    START_SCN,
                    ConfigDef.Type.LONG,
                    0L,
                    Importance.HIGH,
                    "Start SCN, if set to 0 an initial intake from the tables will be performed."
                )
                .define(
                    DB_ATTEMPTS,
                    ConfigDef.Type.INT,
                    3,
                    Importance.LOW,
                    "Maximum number of attempts to retrieve a valid JDBC connection."
                )
                .define(
                    DB_BACKOFF_MS,
                    ConfigDef.Type.LONG,
                    10000L,
                    Importance.LOW,
                    "Backoff time in milliseconds between connection attempts."
                )
                .define(
                    POLL_INTERVAL_MS,
                    ConfigDef.Type.LONG,
                    2000L,
                    Importance.LOW,
                    "Positive integer value that specifies the number of milliseconds the connector should wait after a polling attempt didn't retrieve any results."
                )
        }
    }
}

fun main() {
    println(SourceConnectorConfig.conf().toEnrichedRst())
}
