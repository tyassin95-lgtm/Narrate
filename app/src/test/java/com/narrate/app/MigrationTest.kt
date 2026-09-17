package com.narrate.app

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.narrate.app.data.db.NarrateDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * An existing save must survive the upgrade that added usage tracking and play styles.
 * This runs the migration against a database shaped like version 1 and checks that the
 * player's world is still there afterwards.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MigrationTest {

    private lateinit var helper: SupportSQLiteOpenHelper
    private lateinit var db: SupportSQLiteDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.deleteDatabase(DB_NAME)
        helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(DB_NAME)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
        db = helper.writableDatabase
        // The version 1 worlds table, with no playStyle column and no usage table.
        db.execSQL(
            """
            CREATE TABLE worlds (
                id TEXT NOT NULL PRIMARY KEY,
                name TEXT NOT NULL,
                storyTime TEXT NOT NULL,
                turnCount INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("INSERT INTO worlds VALUES ('w1', 'Tidewater', 'Day 4, dusk', 11)")
    }

    @After
    fun tearDown() {
        helper.close()
    }

    @Test
    fun `the world survives and gains a default play style`() {
        NarrateDatabase.MIGRATION_1_2.migrate(db)

        db.query("SELECT id, name, storyTime, turnCount, playStyle FROM worlds").use { cursor ->
            assertTrue("the existing world must still be there", cursor.moveToFirst())
            assertEquals("w1", cursor.getString(0))
            assertEquals("Tidewater", cursor.getString(1))
            assertEquals("Day 4, dusk", cursor.getString(2))
            assertEquals(11, cursor.getInt(3))
            assertEquals("a pre-existing world defaults to balanced pacing", "BALANCED", cursor.getString(4))
            assertEquals(1, cursor.count)
        }
    }

    @Test
    fun `usage tracking is created and writable`() {
        NarrateDatabase.MIGRATION_1_2.migrate(db)

        db.execSQL(
            "INSERT INTO usage_events VALUES ('u1','w1',3,'NARRATION','OPENAI','gpt-5.4',1200,400,0,0.0042,1,123456)"
        )
        db.query("SELECT worldId, model, inputTokens, estimatedCost, costKnown FROM usage_events").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("w1", cursor.getString(0))
            assertEquals("gpt-5.4", cursor.getString(1))
            assertEquals(1200, cursor.getInt(2))
            assertEquals(0.0042, cursor.getDouble(3), 0.00001)
            assertEquals(1, cursor.getInt(4))
        }
        db.query("SELECT name FROM sqlite_master WHERE type='index' AND name='index_usage_events_worldId'")
            .use { assertTrue("the world index must exist", it.moveToFirst()) }
    }

    @Test
    fun `running the migration is safe if the tables already exist`() {
        NarrateDatabase.MIGRATION_1_2.migrate(db)
        db.execSQL("CREATE TABLE IF NOT EXISTS usage_events (id TEXT NOT NULL PRIMARY KEY)")
        db.query("SELECT count(*) FROM usage_events").use {
            assertTrue(it.moveToFirst())
            assertEquals(0, it.getInt(0))
        }
    }

    @Test
    fun `the authored canon columns are added without touching existing rows`() {
        NarrateDatabase.MIGRATION_1_2.migrate(db)
        // A version 2 characters table, as an installed copy of the app would have it.
        db.execSQL(
            """
            CREATE TABLE characters (
                id TEXT NOT NULL PRIMARY KEY,
                worldId TEXT NOT NULL,
                name TEXT NOT NULL,
                appearance TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("INSERT INTO characters VALUES ('c1', 'w1', 'Adrian Voss', 'Dark hair, green eyes')")

        NarrateDatabase.MIGRATION_2_3.migrate(db)

        db.query("SELECT name, appearance, authoredCanon FROM characters").use { cursor ->
            assertTrue("the existing character must survive", cursor.moveToFirst())
            assertEquals("Adrian Voss", cursor.getString(0))
            assertEquals("Dark hair, green eyes", cursor.getString(1))
            assertEquals("a world made before this feature has no authored text", "", cursor.getString(2))
        }
        db.query("SELECT playStyle, authoredCanon FROM worlds").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("BALANCED", cursor.getString(0))
            assertEquals("", cursor.getString(1))
        }
    }

    @Test
    fun `a save from the first release upgrades all the way without losing anything`() {
        NarrateDatabase.MIGRATION_1_2.migrate(db)
        db.execSQL("CREATE TABLE characters (id TEXT NOT NULL PRIMARY KEY, worldId TEXT NOT NULL, name TEXT NOT NULL)")
        db.execSQL("INSERT INTO characters VALUES ('c1', 'w1', 'Vale')")
        db.execSQL(
            "INSERT INTO usage_events VALUES ('u1','w1',3,'NARRATION','OPENAI','gpt-5.4',1200,400,0,0.0042,1,123456)"
        )
        NarrateDatabase.MIGRATION_2_3.migrate(db)

        db.query("SELECT id, name, storyTime, turnCount FROM worlds").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Tidewater", cursor.getString(1))
            assertEquals(11, cursor.getInt(3))
        }
        db.query("SELECT count(*) FROM usage_events").use {
            assertTrue(it.moveToFirst())
            assertEquals("usage history survives the second upgrade", 1, it.getInt(0))
        }
    }

    private companion object {
        const val DB_NAME = "migration-test.db"
    }
}
