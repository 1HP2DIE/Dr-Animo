package com.animo.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.animo.app.data.local.dao.AnimeDao
import com.animo.app.data.local.dao.EpisodeDao
import com.animo.app.data.local.dao.ProgressDao
import com.animo.app.data.local.entity.AnimeEntity
import com.animo.app.data.local.entity.AnimeFtsEntity
import com.animo.app.data.local.entity.EpisodeEntity
import com.animo.app.data.local.entity.ProgressEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Room is the single source of truth for every list the UI renders (ARCHITECTURE.md: "cache or
 * crash"). The DB file is a rebuildable cache of the API — losing it costs a 3-minute sync, nothing
 * more, so there is no migration history to maintain at version 1.
 */
@Database(
    entities = [
        AnimeEntity::class,
        EpisodeEntity::class,
        ProgressEntity::class,
        AnimeFtsEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AnimoDatabase : RoomDatabase() {

    abstract fun animeDao(): AnimeDao

    abstract fun episodeDao(): EpisodeDao

    abstract fun progressDao(): ProgressDao

    /**
     * `INSERT INTO anime_fts(anime_fts) VALUES('rebuild')` — the external-content FTS4 maintenance
     * command. Room's sync triggers already handle single-row upserts; this repairs the whole index
     * after a bulk page batch, where the trigger path is slower than a rebuild.
     */
    suspend fun rebuildSearchIndex() = withContext(Dispatchers.IO) {
        openHelper.writableDatabase.execSQL(REBUILD_FTS)
    }

    companion object {
        const val DB_NAME = "animo.db"

        private const val REBUILD_FTS = "INSERT INTO anime_fts(anime_fts) VALUES('rebuild')"

        fun build(context: Context): AnimoDatabase =
            Room.databaseBuilder(context.applicationContext, AnimoDatabase::class.java, DB_NAME)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build()
    }
}
