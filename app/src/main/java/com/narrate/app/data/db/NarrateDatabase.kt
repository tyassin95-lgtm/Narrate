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
        UsageEntity::class,
        KnowledgeEntity::class,
        EventEntity::class
    ],
    version = 7,
    // The schema is written to app/schemas on every build. Every version needs a migration,
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
    abstract fun knowledgeDao(): KnowledgeDao
    abstract fun eventDao(): EventDao

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

        /**
         * Records which people the player can actually contact remotely.
         *
         * Existing saves start with nobody reachable, which is the honest answer: the app was
         * not tracking it before, so nothing about it was established. A number exchanged in an
         * earlier scene is re-established the next time it comes up in play.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE characters ADD COLUMN playerContact TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * Adds the player-knowledge table and real possession state for objects.
         *
         * Existing saves are treated generously rather than blanked: everything already on
         * their map stays on their map, everyone they have met stays met. Starting a played
         * world over with an empty knowledge table would hide places the player has walked
         * through, which is a worse lie than the one this release is fixing.
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE items ADD COLUMN possession TEXT NOT NULL DEFAULT 'HELD'")
                db.execSQL("ALTER TABLE items ADD COLUMN history TEXT NOT NULL DEFAULT ''")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS player_knowledge (
                        id TEXT NOT NULL PRIMARY KEY,
                        worldId TEXT NOT NULL,
                        subjectType TEXT NOT NULL,
                        subjectId TEXT NOT NULL,
                        subjectName TEXT NOT NULL DEFAULT '',
                        field TEXT NOT NULL,
                        value TEXT NOT NULL DEFAULT '',
                        source TEXT NOT NULL DEFAULT 'SEEN',
                        sourceDetail TEXT NOT NULL DEFAULT '',
                        turnIndex INTEGER NOT NULL DEFAULT 0,
                        storyTime TEXT NOT NULL DEFAULT '',
                        createdAt INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_player_knowledge_worldId ON player_knowledge(worldId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_player_knowledge_subjectId ON player_knowledge(subjectId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_player_knowledge_subjectType ON player_knowledge(subjectType)")

                // A world that has already been played keeps what it has shown the player.
                db.execSQL(
                    """
                    INSERT INTO player_knowledge (id, worldId, subjectType, subjectId, subjectName, field, value, source, sourceDetail, turnIndex, storyTime, createdAt)
                    SELECT hex(randomblob(16)), worldId, 'LOCATION', id, name, 'exists', '', 'VISITED', 'already on the map before this version', 0, '', 0
                    FROM locations WHERE discovered = 1
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO player_knowledge (id, worldId, subjectType, subjectId, subjectName, field, value, source, sourceDetail, turnIndex, storyTime, createdAt)
                    SELECT hex(randomblob(16)), worldId, 'CHARACTER', id, name, 'exists', '', 'SEEN', 'already met before this version', 0, '', 0
                    FROM characters WHERE isPlayer = 0 AND lastSeenTurn > 0
                    """.trimIndent()
                )
            }
        }

        /**
         * One authoritative clock, a calendar to point it at, clothing that knows when it was
         * put on, and geography that is geography rather than a tree.
         *
         * An existing save is anchored at its current day and time: the clock is set from the
         * day number and time of day it already had, on a calendar epoch chosen so the world
         * keeps running from where the player left it.
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE worlds ADD COLUMN clockMinute INTEGER NOT NULL DEFAULT 1260")
                db.execSQL("ALTER TABLE worlds ADD COLUMN calendarEpoch TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE characters ADD COLUMN outfitSetAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE characters ADD COLUMN outfitContext TEXT NOT NULL DEFAULT 'CASUAL'")
                db.execSQL("ALTER TABLE characters ADD COLUMN temporaryLook TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE locations ADD COLUMN spanAngle REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE locations ADD COLUMN spanLength REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE locations ADD COLUMN streetId TEXT")
                db.execSQL("ALTER TABLE locations ADD COLUMN addressNumber INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE memories ADD COLUMN provenance TEXT NOT NULL DEFAULT 'OBSERVED'")
                // What the player wrote keeps the standing it always had.
                db.execSQL("UPDATE memories SET provenance = 'PLAYER_CANON' WHERE kind = 'CANON'")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS events (
                        id TEXT NOT NULL PRIMARY KEY,
                        worldId TEXT NOT NULL,
                        title TEXT NOT NULL,
                        description TEXT NOT NULL DEFAULT '',
                        kind TEXT NOT NULL DEFAULT 'PLAN',
                        startMinute INTEGER NOT NULL DEFAULT 0,
                        durationMinutes INTEGER NOT NULL DEFAULT 60,
                        recurrence TEXT NOT NULL DEFAULT '',
                        locationId TEXT,
                        locationName TEXT NOT NULL DEFAULT '',
                        withNames TEXT NOT NULL DEFAULT '',
                        status TEXT NOT NULL DEFAULT 'SCHEDULED',
                        knownToPlayer INTEGER NOT NULL DEFAULT 1,
                        forPlayer INTEGER NOT NULL DEFAULT 1,
                        createdTurn INTEGER NOT NULL DEFAULT 0,
                        createdAt INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_events_worldId ON events(worldId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_events_startMinute ON events(startMinute)")

                // Anchor the clock where the world already is, so no save loses its evening.
                db.execSQL(
                    """
                    UPDATE worlds SET clockMinute = (MAX(dayNumber, 1) - 1) * 1440 + CASE
                        WHEN timeOfDay LIKE '%dawn%' OR timeOfDay LIKE '%sunrise%' THEN 330
                        WHEN timeOfDay LIKE '%morning%' THEN 540
                        WHEN timeOfDay LIKE '%midday%' OR timeOfDay LIKE '%noon%' THEN 720
                        WHEN timeOfDay LIKE '%afternoon%' THEN 900
                        WHEN timeOfDay LIKE '%dusk%' OR timeOfDay LIKE '%sunset%' THEN 1140
                        WHEN timeOfDay LIKE '%evening%' THEN 1230
                        WHEN timeOfDay LIKE '%midnight%' THEN 1440
                        WHEN timeOfDay LIKE '%night%' THEN 1320
                        ELSE 1260 END
                    """.trimIndent()
                )
            }
        }

        fun get(context: Context): NarrateDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                NarrateDatabase::class.java,
                "narrate.db"
            )
                // A save is the player's world. Never destroy one on a routine upgrade.
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
                .fallbackToDestructiveMigrationOnDowngrade()
                .build()
                .also { instance = it }
        }
    }
}
