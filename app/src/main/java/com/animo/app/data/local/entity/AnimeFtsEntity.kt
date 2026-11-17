package com.animo.app.data.local.entity

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions

/**
 * FTS4 external-content index over [AnimeEntity].
 *
 * FTS4 (not FTS5) because FTS5 availability is a runtime property of the bundled SQLite, while FTS4
 * with `unicode61` is present on every API level we ship (minSdk 24). `content='anime'` means the
 * index stores no copy of the text: rowids stay in lockstep with `anime.id`, which is exactly what
 * the JOIN in `AnimeDao.observeSearch` relies on.
 *
 * Room generates the `room_fts_content_sync_*` triggers for external-content tables, so ordinary
 * upserts keep the index fresh; `rebuild` is still issued after a bulk batch as a belt-and-braces
 * repair.
 */
@Fts4(
    contentEntity = AnimeEntity::class,
    tokenizer = FtsOptions.TOKENIZER_UNICODE61,
)
@Entity(tableName = "anime_fts")
data class AnimeFtsEntity(
    val title: String,
    val alternative: String,
    val titles: String,
    val native: String,
    val genres: String,
    val studios: String,
)
