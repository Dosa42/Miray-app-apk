package com.example.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.model.AIProviderConfig
import com.example.model.AgentMemory
import com.example.model.AppSettingsEntity
import com.example.model.AutomationScript
import com.example.model.ChatMessageEntity
import com.example.model.ExecutionLog
import com.example.model.MemoryLearningQueueEntity

@Database(
    entities = [
        ChatMessageEntity::class,
        AgentMemory::class,
        AutomationScript::class,
        ExecutionLog::class,
        AIProviderConfig::class,
        AppSettingsEntity::class,
        MemoryLearningQueueEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun agentMemoryDao(): AgentMemoryDao
    abstract fun automationScriptDao(): AutomationScriptDao
    abstract fun executionLogDao(): ExecutionLogDao
    abstract fun aiProviderConfigDao(): AIProviderConfigDao
    abstract fun appSettingsDao(): AppSettingsDao
    abstract fun memoryLearningQueueDao(): MemoryLearningQueueDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "termux_agent_database"
                )
                .addMigrations(MIGRATION_2_3)
                .build()
                INSTANCE = instance
                instance
            }
        }

        /**
         * Loss-intolerant V2→V3 migration. Room wraps this migration in a transaction.
         * Critical V2 tables are copied first and every pre/post count must match.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                val protectedTables = listOf(
                    "agent_memories",
                    "chat_message_entities",
                    "automation_scripts",
                    "provider_configs",
                    "app_settings"
                )

                val beforeCounts = protectedTables.associateWith { table -> count(database, table) }
                protectedTables.forEach { table ->
                    val backup = "migration_v2_backup_$table"
                    database.execSQL("CREATE TABLE `$backup` AS SELECT * FROM `$table`")
                    val backupCount = count(database, backup)
                    check(backupCount == beforeCounts.getValue(table)) {
                        "V2 backup verification failed for $table: expected ${beforeCounts.getValue(table)}, copied $backupCount"
                    }
                }

                database.execSQL("ALTER TABLE `agent_memories` ADD COLUMN `stableKey` TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE `agent_memories` ADD COLUMN `isPinned` INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE `agent_memories` ADD COLUMN `source` TEXT NOT NULL DEFAULT 'manual'")
                database.execSQL("UPDATE `agent_memories` SET `stableKey` = 'legacy-' || `id` WHERE `stableKey` = ''")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_agent_memories_stableKey` ON `agent_memories` (`stableKey`)")

                database.execSQL("ALTER TABLE `chat_message_entities` ADD COLUMN `turnId` TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE `chat_message_entities` ADD COLUMN `isComplete` INTEGER NOT NULL DEFAULT 1")
                database.execSQL("ALTER TABLE `chat_message_entities` ADD COLUMN `rawResponse` TEXT NOT NULL DEFAULT ''")

                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `memory_learning_queue` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `turnKey` TEXT NOT NULL,
                        `evidenceJson` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `attemptCount` INTEGER NOT NULL,
                        `lastError` TEXT NOT NULL,
                        `decision` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_memory_learning_queue_turnKey` ON `memory_learning_queue` (`turnKey`)")

                protectedTables.forEach { table ->
                    val after = count(database, table)
                    val backup = count(database, "migration_v2_backup_$table")
                    check(after == beforeCounts.getValue(table) && backup == beforeCounts.getValue(table)) {
                        "V2→V3 data loss detected for $table: before=${beforeCounts.getValue(table)}, after=$after, backup=$backup"
                    }
                }
            }
        }

        private fun count(database: SupportSQLiteDatabase, table: String): Long {
            database.query("SELECT COUNT(*) FROM `$table`").use { cursor ->
                check(cursor.moveToFirst()) { "Could not count table $table" }
                return cursor.getLong(0)
            }
        }
    }
}
