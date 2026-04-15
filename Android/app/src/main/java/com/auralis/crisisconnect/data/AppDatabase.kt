package com.auralis.crisisconnect.data

import android.content.Context
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.util.Log
import com.auralis.crisisconnect.data.database.LocalKeyStorage
import androidx.room.Database
import androidx.room.migration.Migration
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import net.zetetic.database.sqlcipher.SQLiteNotADatabaseException
import java.io.File

@Database(
    entities = [ContactEntity::class, MessageEntity::class, CallEventEntity::class],
    version = 17
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun contactDao(): ContactDao
    abstract fun messageDao(): MessageDao
    abstract fun callEventDao(): CallEventDao

    companion object {
        private const val TAG = "AppDatabase"
        private const val DB_NAME = "app_database"
        private const val DB_BACKUP_DIR = "db_recovery_backups"
        @Volatile
        private var SQLCIPHER_LOADED = false

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            INSTANCE?.let { existing ->
                return existing
            }

            return synchronized(this) {
                INSTANCE?.let { existing ->
                    return@synchronized existing
                }

                val appContext = context.applicationContext
                ensureSqlCipherLoaded()
                val passphrase = buildSqlCipherPassphrase(appContext)
                val instance = runCatching {
                    openDatabaseWithRecovery(appContext, passphrase)
                }.getOrElse { error ->
                    Log.e(TAG, "Failed to open encrypted database", error)
                    throw IllegalStateException(
                        "Unable to open encrypted database.",
                        error
                    )
                }
                INSTANCE = instance
                instance
            }
        }

        private fun openDatabaseWithRecovery(
            context: Context,
            passphrase: ByteArray
        ): AppDatabase {
            return runCatching {
                createAndOpenDatabase(context, passphrase)
            }.recoverCatching { error ->
                if (!shouldResetDatabase(error)) {
                    throw error
                }
                val backupDir = backupDatabaseFiles(context)
                if (backupDir != null) {
                    Log.w(
                        TAG,
                        "Encrypted database open failed; backed up files to ${backupDir.absolutePath} before reset",
                        error
                    )
                } else {
                    Log.w(
                        TAG,
                        "Encrypted database open failed; no backup files were created before reset",
                        error
                    )
                }
                resetDatabaseFiles(context)
                createAndOpenDatabase(context, passphrase)
            }.getOrThrow()
        }

        private fun createAndOpenDatabase(
            context: Context,
            passphrase: ByteArray
        ): AppDatabase {
            val created = buildDatabase(context, passphrase)
            try {
                created.openHelper.writableDatabase
            } catch (error: Throwable) {
                runCatching { created.close() }
                throw error
            }
            return created
        }

        private fun shouldResetDatabase(error: Throwable): Boolean {
            return generateSequence(error) { throwable -> throwable.cause }.any { throwable ->
                throwable is SQLiteNotADatabaseException ||
                    throwable is SQLiteDatabaseCorruptException ||
                    throwable.message?.contains("file is not a database", ignoreCase = true) == true ||
                    throwable.message?.contains("hmac check failed", ignoreCase = true) == true ||
                    throwable.message?.contains("error decrypting page", ignoreCase = true) == true
            }
        }

        private fun backupDatabaseFiles(context: Context): File? {
            val dbFile = context.getDatabasePath(DB_NAME)
            val candidates = listOf(
                dbFile,
                File("${dbFile.path}-wal"),
                File("${dbFile.path}-shm"),
                File("${dbFile.path}-journal")
            ).filter { it.exists() }
            if (candidates.isEmpty()) {
                return null
            }

            val backupRoot = File(context.noBackupFilesDir, DB_BACKUP_DIR)
            if (!backupRoot.exists() && !backupRoot.mkdirs()) {
                Log.w(TAG, "Unable to create database backup root: ${backupRoot.absolutePath}")
                return null
            }

            val backupDir = File(backupRoot, "${DB_NAME}_${System.currentTimeMillis()}")
            if (!backupDir.mkdirs()) {
                Log.w(TAG, "Unable to create database backup directory: ${backupDir.absolutePath}")
                return null
            }

            var copiedAnyFile = false
            candidates.forEach { source ->
                val target = File(backupDir, source.name)
                runCatching {
                    source.inputStream().use { input ->
                        target.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    target.setLastModified(source.lastModified())
                    copiedAnyFile = true
                }.onFailure { throwable ->
                    Log.w(TAG, "Unable to back up database file ${source.absolutePath}", throwable)
                }
            }
            return backupDir.takeIf { copiedAnyFile }
        }

        private fun resetDatabaseFiles(context: Context) {
            if (!context.deleteDatabase(DB_NAME)) {
                Log.w(TAG, "deleteDatabase($DB_NAME) returned false; trying manual cleanup")
            }
            val dbFile = context.getDatabasePath(DB_NAME)
            val sidecarFiles = listOf(
                dbFile,
                File("${dbFile.path}-wal"),
                File("${dbFile.path}-shm"),
                File("${dbFile.path}-journal")
            )
            sidecarFiles.forEach { file ->
                if (file.exists() && !file.delete()) {
                    Log.w(TAG, "Unable to delete database file: ${file.absolutePath}")
                }
            }
        }

        private fun buildDatabase(
            context: Context,
            passphrase: ByteArray
        ): AppDatabase {
            val builder = Room.databaseBuilder(
                context,
                AppDatabase::class.java,
                DB_NAME
            )
                .openHelperFactory(SupportOpenHelperFactory(passphrase))
                .addMigrations(
                    MIGRATION_6_7,
                    MIGRATION_7_8,
                    MIGRATION_8_9,
                    MIGRATION_9_10,
                    MIGRATION_10_11,
                    MIGRATION_11_12,
                    MIGRATION_12_13,
                    MIGRATION_13_14,
                    MIGRATION_14_15,
                    MIGRATION_15_16,
                    MIGRATION_16_17
                )
                .addCallback(object : RoomDatabase.Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        super.onOpen(db)
                        db.execSQL("PRAGMA foreign_keys=ON")
                    }
                })
            return builder
                .build()
        }

        private fun ensureSqlCipherLoaded() {
            if (SQLCIPHER_LOADED) {
                return
            }
            synchronized(this) {
                if (SQLCIPHER_LOADED) {
                    return
                }
                System.loadLibrary("sqlcipher")
                SQLCIPHER_LOADED = true
            }
        }

        private fun buildSqlCipherPassphrase(context: Context): ByteArray {
            val seed = runCatching {
                LocalKeyStorage.getOrCreateSqlCipherKey(context)
            }.getOrElse {
                throw IllegalStateException(
                    "Unable to derive SQLCipher key from secure local storage.",
                    it
                )
            }
            return seed.toByteArray(Charsets.UTF_8)
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN deliveryStatus TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN retryCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE messages ADD COLUMN nextRetryAtMillis INTEGER")
                db.execSQL("ALTER TABLE messages ADD COLUMN lastAttemptAtMillis INTEGER")
                db.execSQL("ALTER TABLE messages ADD COLUMN lastError TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN outboundRoute TEXT")
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN originalTimestampMillis INTEGER")
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("PRAGMA foreign_keys=OFF")
                db.execSQL(
                    "INSERT OR IGNORE INTO contacts(sessionCode, name, aesKey, address) " +
                        "SELECT DISTINCT sessionCode, sessionCode, '', '' FROM messages"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `messages_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `sessionCode` TEXT NOT NULL,
                        `messageUuid` TEXT NOT NULL,
                        `text` TEXT NOT NULL,
                        `messageType` TEXT NOT NULL,
                        `audioFilePath` TEXT,
                        `audioDurationMillis` INTEGER,
                        `imageFilePath` TEXT,
                        `imageThumbnailPath` TEXT,
                        `imageWidth` INTEGER,
                        `imageHeight` INTEGER,
                        `imageMimeType` TEXT,
                        `isLocal` INTEGER NOT NULL,
                        `isRead` INTEGER NOT NULL,
                        `timestampMillis` INTEGER NOT NULL,
                        `originalTimestampMillis` INTEGER,
                        `deliveryStatus` TEXT,
                        `retryCount` INTEGER NOT NULL,
                        `nextRetryAtMillis` INTEGER,
                        `lastAttemptAtMillis` INTEGER,
                        `lastError` TEXT,
                        `outboundRoute` TEXT,
                        FOREIGN KEY(`sessionCode`) REFERENCES `contacts`(`sessionCode`) ON UPDATE CASCADE ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_messages_sessionCode` ON `messages_new` (`sessionCode`)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_messages_messageUuid` ON `messages_new` (`messageUuid`)"
                )
                db.execSQL(
                    "INSERT INTO `messages_new` (" +
                        "`id`, `sessionCode`, `messageUuid`, `text`, `messageType`, " +
                        "`audioFilePath`, `audioDurationMillis`, `imageFilePath`, `imageThumbnailPath`, " +
                        "`imageWidth`, `imageHeight`, `imageMimeType`, `isLocal`, `isRead`, " +
                        "`timestampMillis`, `originalTimestampMillis`, `deliveryStatus`, `retryCount`, " +
                        "`nextRetryAtMillis`, `lastAttemptAtMillis`, `lastError`, `outboundRoute`" +
                        ") SELECT " +
                        "`id`, `sessionCode`, `messageUuid`, `text`, `messageType`, " +
                        "`audioFilePath`, `audioDurationMillis`, `imageFilePath`, `imageThumbnailPath`, " +
                        "`imageWidth`, `imageHeight`, `imageMimeType`, `isLocal`, `isRead`, " +
                        "`timestampMillis`, `originalTimestampMillis`, `deliveryStatus`, `retryCount`, " +
                        "`nextRetryAtMillis`, `lastAttemptAtMillis`, `lastError`, `outboundRoute` " +
                        "FROM `messages`"
                )
                db.execSQL("DROP TABLE `messages`")
                db.execSQL("ALTER TABLE `messages_new` RENAME TO `messages`")
                db.execSQL("PRAGMA foreign_keys=ON")
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE messages ADD COLUMN sentToRecipients TEXT NOT NULL DEFAULT '[]'"
                )
                db.execSQL(
                    "ALTER TABLE messages ADD COLUMN deliveredToRecipients TEXT NOT NULL DEFAULT '[]'"
                )
                db.execSQL(
                    "ALTER TABLE messages ADD COLUMN readByRecipients TEXT NOT NULL DEFAULT '[]'"
                )
            }
        }

        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN senderDisplayName TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN senderAddress TEXT")
            }
        }

        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                ensureMessageColumn(
                    db = db,
                    columnName = "originalTimestampMillis",
                    definition = "INTEGER"
                )
                ensureMessageColumn(
                    db = db,
                    columnName = "deliveryStatus",
                    definition = "TEXT"
                )
                ensureMessageColumn(
                    db = db,
                    columnName = "retryCount",
                    definition = "INTEGER NOT NULL DEFAULT 0"
                )
                ensureMessageColumn(
                    db = db,
                    columnName = "nextRetryAtMillis",
                    definition = "INTEGER"
                )
                ensureMessageColumn(
                    db = db,
                    columnName = "lastAttemptAtMillis",
                    definition = "INTEGER"
                )
                ensureMessageColumn(
                    db = db,
                    columnName = "lastError",
                    definition = "TEXT"
                )
                ensureMessageColumn(
                    db = db,
                    columnName = "outboundRoute",
                    definition = "TEXT"
                )
                ensureMessageColumn(
                    db = db,
                    columnName = "senderDisplayName",
                    definition = "TEXT"
                )
                ensureMessageColumn(
                    db = db,
                    columnName = "senderAddress",
                    definition = "TEXT"
                )
                ensureMessageColumn(
                    db = db,
                    columnName = "sentToRecipients",
                    definition = "TEXT NOT NULL DEFAULT '[]'"
                )
                ensureMessageColumn(
                    db = db,
                    columnName = "deliveredToRecipients",
                    definition = "TEXT NOT NULL DEFAULT '[]'"
                )
                ensureMessageColumn(
                    db = db,
                    columnName = "readByRecipients",
                    definition = "TEXT NOT NULL DEFAULT '[]'"
                )

                db.execSQL("PRAGMA foreign_keys=OFF")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `messages_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `sessionCode` TEXT NOT NULL,
                        `messageUuid` TEXT NOT NULL,
                        `text` TEXT NOT NULL,
                        `messageType` TEXT NOT NULL,
                        `audioFilePath` TEXT,
                        `audioDurationMillis` INTEGER,
                        `imageFilePath` TEXT,
                        `imageThumbnailPath` TEXT,
                        `imageWidth` INTEGER,
                        `imageHeight` INTEGER,
                        `imageMimeType` TEXT,
                        `isLocal` INTEGER NOT NULL,
                        `isRead` INTEGER NOT NULL,
                        `timestampMillis` INTEGER NOT NULL,
                        `originalTimestampMillis` INTEGER,
                        `deliveryStatus` TEXT,
                        `retryCount` INTEGER NOT NULL DEFAULT 0,
                        `nextRetryAtMillis` INTEGER,
                        `lastAttemptAtMillis` INTEGER,
                        `lastError` TEXT,
                        `outboundRoute` TEXT,
                        `senderDisplayName` TEXT,
                        `senderAddress` TEXT,
                        `sentToRecipients` TEXT NOT NULL DEFAULT '[]',
                        `deliveredToRecipients` TEXT NOT NULL DEFAULT '[]',
                        `readByRecipients` TEXT NOT NULL DEFAULT '[]',
                        FOREIGN KEY(`sessionCode`) REFERENCES `contacts`(`sessionCode`) ON UPDATE CASCADE ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO `messages_new` (
                        `id`,
                        `sessionCode`,
                        `messageUuid`,
                        `text`,
                        `messageType`,
                        `audioFilePath`,
                        `audioDurationMillis`,
                        `imageFilePath`,
                        `imageThumbnailPath`,
                        `imageWidth`,
                        `imageHeight`,
                        `imageMimeType`,
                        `isLocal`,
                        `isRead`,
                        `timestampMillis`,
                        `originalTimestampMillis`,
                        `deliveryStatus`,
                        `retryCount`,
                        `nextRetryAtMillis`,
                        `lastAttemptAtMillis`,
                        `lastError`,
                        `outboundRoute`,
                        `senderDisplayName`,
                        `senderAddress`,
                        `sentToRecipients`,
                        `deliveredToRecipients`,
                        `readByRecipients`
                    )
                    SELECT
                        `id`,
                        `sessionCode`,
                        `messageUuid`,
                        `text`,
                        `messageType`,
                        `audioFilePath`,
                        `audioDurationMillis`,
                        `imageFilePath`,
                        `imageThumbnailPath`,
                        `imageWidth`,
                        `imageHeight`,
                        `imageMimeType`,
                        `isLocal`,
                        `isRead`,
                        `timestampMillis`,
                        `originalTimestampMillis`,
                        `deliveryStatus`,
                        `retryCount`,
                        `nextRetryAtMillis`,
                        `lastAttemptAtMillis`,
                        `lastError`,
                        `outboundRoute`,
                        `senderDisplayName`,
                        `senderAddress`,
                        COALESCE(`sentToRecipients`, '[]'),
                        COALESCE(`deliveredToRecipients`, '[]'),
                        COALESCE(`readByRecipients`, '[]')
                    FROM `messages`
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE `messages`")
                db.execSQL("ALTER TABLE `messages_new` RENAME TO `messages`")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_messages_sessionCode` ON `messages` (`sessionCode`)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_messages_messageUuid` ON `messages` (`messageUuid`)"
                )
                db.execSQL("PRAGMA foreign_keys=ON")
            }
        }

        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                ensureTableColumn(
                    db = db,
                    tableName = "contacts",
                    columnName = "remoteSessionCode",
                    definition = "TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                ensureTableColumn(
                    db = db,
                    tableName = "contacts",
                    columnName = "preferredTransport",
                    definition = "TEXT NOT NULL DEFAULT 'RFCOMM'"
                )
                ensureTableColumn(
                    db = db,
                    tableName = "contacts",
                    columnName = "remotePlatform",
                    definition = "TEXT NOT NULL DEFAULT 'unknown'"
                )
                ensureTableColumn(
                    db = db,
                    tableName = "contacts",
                    columnName = "bleShareId",
                    definition = "TEXT NOT NULL DEFAULT ''"
                )
                ensureTableColumn(
                    db = db,
                    tableName = "contacts",
                    columnName = "lastKnownBleAddress",
                    definition = "TEXT NOT NULL DEFAULT ''"
                )
                ensureTableColumn(
                    db = db,
                    tableName = "contacts",
                    columnName = "remoteDeviceId",
                    definition = "TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                ensureTableColumn(
                    db = db,
                    tableName = "contacts",
                    columnName = "verified",
                    definition = "INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        private val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                ensureTableColumn(
                    db = db,
                    tableName = "contacts",
                    columnName = "verifiedIdentityKey",
                    definition = "TEXT NOT NULL DEFAULT ''"
                )
                ensureTableColumn(
                    db = db,
                    tableName = "contacts",
                    columnName = "verifiedAt",
                    definition = "INTEGER"
                )
                db.execSQL(
                    "UPDATE contacts SET verified = 0, verifiedAt = NULL WHERE verifiedIdentityKey = ''"
                )
            }
        }

        private val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                ensureMessageColumn(
                    db = db,
                    columnName = "originVerifiedRole",
                    definition = "TEXT"
                )
                ensureMessageColumn(
                    db = db,
                    columnName = "originVerifiedAtMillis",
                    definition = "INTEGER"
                )
            }
        }

        private fun ensureMessageColumn(
            db: SupportSQLiteDatabase,
            columnName: String,
            definition: String
        ) {
            if (hasColumn(db, tableName = "messages", columnName = columnName)) {
                return
            }
            db.execSQL("ALTER TABLE `messages` ADD COLUMN `$columnName` $definition")
        }

        private fun ensureTableColumn(
            db: SupportSQLiteDatabase,
            tableName: String,
            columnName: String,
            definition: String
        ) {
            if (hasColumn(db, tableName = tableName, columnName = columnName)) {
                return
            }
            db.execSQL("ALTER TABLE `$tableName` ADD COLUMN `$columnName` $definition")
        }

        private fun hasColumn(
            db: SupportSQLiteDatabase,
            tableName: String,
            columnName: String
        ): Boolean {
            db.query("PRAGMA table_info(`$tableName`)").use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                if (nameIndex == -1) {
                    return false
                }
                while (cursor.moveToNext()) {
                    if (cursor.getString(nameIndex) == columnName) {
                        return true
                    }
                }
            }
            return false
        }
    }
}
