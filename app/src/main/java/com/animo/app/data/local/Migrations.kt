package com.animo.app.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.animo.app.data.remote.Normalize

/**
 * v1 → v2: adds `anime.titleNorm`, the browse/sort key behind the A–Z filter and the title sorts.
 *
 * Why a column instead of an expression: the letter filter must **strip leading punctuation** and
 * fold case, which SQLite cannot do sanely in a `WHERE` (`LOWER` is ASCII-only and `ltrim` on a
 * character set is not the same rule). Computing it once at ingest keeps a single definition
 * ([Normalize.titleSortKey]) shared by the API mapper, this backfill and the facet lists — and it
 * makes `ORDER BY titleNorm` an index-friendly sort rather than a runtime scan.
 *
 * Non-destructive: every existing row keeps its data. `ADD COLUMN` cannot carry a non-constant
 * default, so `DEFAULT ''` is used to satisfy `NOT NULL` and the real values are written in Kotlin
 * immediately afterwards, inside the transaction Room already wraps around a migration. The default
 * itself is invisible to Room's schema check because the entity column has no `@ColumnInfo`
 * default (see `TableInfo.Column` — the comparison only runs when the *entity* side declares one).
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `anime` ADD COLUMN `titleNorm` TEXT NOT NULL DEFAULT ''")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_anime_titleNorm` ON `anime` (`titleNorm`)")
        backfillTitleNorm(db)
    }
}

/**
 * v2 → v3: adds `anime.originUrl`, the provider page behind a Hindi-dub row.
 *
 * Deliberately the *only* new column. Hindi rows are recognised by a negative primary key (see
 * `Anime.isHindi`) rather than an `isHindi` flag, so there is no second source of truth to keep in
 * step: the id range already tells the sync, the resolver and the "Hindi only" filter the same
 * story. Existing rows get `''`, which is exactly the "original catalogue" value.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `anime` ADD COLUMN `originUrl` TEXT NOT NULL DEFAULT ''")
    }
}

/** v3 → v4: the `favorite` table. Purely additive, nothing existing is touched. */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `favorite` (`animeId` INTEGER NOT NULL, `addedAtMs` INTEGER NOT NULL, PRIMARY KEY(`animeId`))",
        )
    }
}

/**
 * v4 → v5: the `sync_cursor` table, the resume point of a paged catalogue walk.
 *
 * Purely additive and one row per provider, so no install loses anything and no backfill is needed:
 * a provider with no row is simply "never walked", which every walker already treats as page 1.
 * The column types and NOT NULL flags mirror [com.animo.app.data.local.entity.SyncCursorEntity]
 * exactly, because Room compares the migrated table against the entity at open time.
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `sync_cursor` " +
                "(`provider` TEXT NOT NULL, `lastPage` INTEGER NOT NULL, " +
                "`lastSyncEpoch` INTEGER NOT NULL, PRIMARY KEY(`provider`))",
        )
    }
}

/**
 * Reads `id, title` first and writes afterwards: executing the `UPDATE` while the cursor is still
 * open on `anime` would reset it. Each write also fires Room's external-content FTS trigger, which
 * re-inserts the indexed columns for that rowid — harmless, and bounded by the catalogue size
 * (~600 rows), which is the whole reason a schema change is affordable at all.
 */
private fun backfillTitleNorm(db: SupportSQLiteDatabase) {
    val rows = ArrayList<Pair<Int, String>>(256)
    db.query("SELECT `id`, `title` FROM `anime`").use { cursor ->
        while (cursor.moveToNext()) {
            rows += cursor.getInt(0) to cursor.getString(1).orEmpty()
        }
    }
    if (rows.isEmpty()) return

    val update = db.compileStatement("UPDATE `anime` SET `titleNorm` = ? WHERE `id` = ?")
    try {
        for ((id, title) in rows) {
            update.bindString(1, Normalize.titleSortKey(title))
            update.bindLong(2, id.toLong())
            update.executeUpdateDelete()
        }
    } finally {
        update.close()
    }
}
