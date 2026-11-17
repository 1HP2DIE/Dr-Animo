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
