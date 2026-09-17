package com.narrate.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.narrate.app.data.dao.*
import com.narrate.app.data.entity.*

@Database(
    entities = [
        WorldEntity::class,
        CharacterEntity::class,
        LocationEntity::class,
        LocationLinkEntity::class,
        ItemEntity::class,
        FactionEntity::class,
        RelationshipEntity::class,
        MemoryEntity::class,
        TurnEntity::class,
        ThreadEntity::class,
        ChapterEntity::class,
        ImageEntity::class,
        VisualIdentityEntity::class,
        ContinuityIssueEntity::class,
        UsageEntity::class
    ],
    version = 4,
    // The schema is written to app/schemas on every build. Version 5 will need a migration,
    // and a migration is only as good as the record of what it is migrating from.
    exportSchema = true
)
abstract class NarrateDatabase : RoomDatabase() {
    abstract fun worldDao(): WorldDao
    abstract fun characterDao(): CharacterDao
    abstract fun locationDao(): LocationDao
    abstract fun locationLinkDao(): LocationLinkDao
    abstract fun itemDao(): ItemDao
    abstract fun factionDao(): FactionDao
    abstract fun relationshipDao(): RelationshipDao
    abstract fun memoryDao(): MemoryDao
    abstract fun turnDao(): TurnDao
    abstract fun threadDao(): ThreadDao
    abstract fun chapterDao(): ChapterDao
    abstract fun imageDao(): ImageDao
    abstract fun visualIdentityDao(): VisualIdentityDao
    abstract fun continuityIssueDao(): ContinuityIssueDao
    abstract fun usageDao(): UsageDao

    companion object {
        @Volatile private var instance: NarrateDatabase? = null

        /**
         * Adds usage tracking and the world's play style. Existing saves keep every row:
         * a world that predates this migration simply starts out BALANCED.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE worlds ADD COLUMN playStyle TEXT NOT NULL DEFAULT 'BALANCED'"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS usage_events (
                        id TEXT NOT NULL PRIMARY KEY,
                        worldId TEXT NOT NULL,
                        turnIndex INTEGER NOT NULL,
                        purpose TEXT NOT NULL,
                        provider TEXT NOT NULL,
                        model TEXT NOT NULL,
                        inputTokens INTEGER NOT NULL,
                        outputTokens INTEGER NOT NULL,
                        images INTEGER NOT NULL,
                        estimatedCost REAL NOT NULL,
                        costKnown INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_usage_events_worldId ON usage_events (worldId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_usage_events_createdAt ON usage_events (createdAt)")
            }
        }

        /**
         * Adds the verbatim text the player wrote for a world and for their character.
         * Existing saves keep everything; they simply have no authored text on record.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE worlds ADD COLUMN authoredCanon TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE characters ADD COLUMN authoredCanon TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * Separates who owns an object from who is carrying it. Existing objects are treated
         * as owned by whoever holds them, which is right for everything except a loan in
         * progress - and that could not have been recorded before this migration anyway.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE items ADD COLUMN ownerId TEXT")
                db.execSQL("UPDATE items SET ownerId = holderId WHERE holderId IS NOT NULL")
            }
        }

        fun get(context: Context): NarrateDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                NarrateDatabase::class.java,
                "narrate.db"
            )
                // A save is the player's world. Never destroy one on a routine upgrade.
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .fallbackToDestructiveMigrationOnDowngrade()
                .build()
                .also { instance = it }
        }
    }
}
