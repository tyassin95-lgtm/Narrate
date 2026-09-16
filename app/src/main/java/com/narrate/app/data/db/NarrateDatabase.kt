package com.narrate.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
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
        ContinuityIssueEntity::class
    ],
    version = 1,
    exportSchema = false
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

    companion object {
        @Volatile private var instance: NarrateDatabase? = null

        fun get(context: Context): NarrateDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                NarrateDatabase::class.java,
                "narrate.db"
            )
                // A save is the player's world. Never destroy one on a routine upgrade.
                .fallbackToDestructiveMigrationOnDowngrade()
                .build()
                .also { instance = it }
        }
    }
}
