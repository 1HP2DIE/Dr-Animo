package com.animo.app.data.update

import com.animo.app.ui.changelog.Change
import com.animo.app.ui.changelog.ChangeKind
import com.animo.app.ui.changelog.Release
import kotlinx.serialization.Serializable

/**
 * The release feed published with the landing page (`docs/update.json`), generated from GitHub
 * releases by `tools/update_feed.py`.
 *
 * Everything here is remote input: every field has a default and the parser ignores unknown keys, so
 * a future feed that grows a field still loads on an old build instead of throwing. Progress fields
 * are `Int` version codes, never version strings — `1.10.0 < 1.9.0` as text, and that comparison
 * decides whether the user gets an update notice.
 */
@Serializable
data class UpdateFeed(
    val schema: Int = 1,
    val generated: String = "",
    val repo: String = "",
    val latest: LatestRelease? = null,
    val releases: List<FeedRelease> = emptyList(),
)

@Serializable
data class LatestRelease(
    val version: String = "",
    val versionCode: Int = 0,
    val date: String = "",
    val headline: String = "",
    val apkUrl: String = "",
    val sizeBytes: Long = 0L,
    val releaseUrl: String = "",
)

@Serializable
data class FeedRelease(
    val version: String = "",
    val versionCode: Int = 0,
    val tag: String = "",
    val date: String = "",
    val headline: String = "",
    val apkUrl: String = "",
    val sizeBytes: Long = 0L,
    val releaseUrl: String = "",
    val changes: List<FeedChange> = emptyList(),
)

@Serializable
data class FeedChange(
    val kind: String = "",
    val text: String = "",
)

/** Feed rows are mapped onto the same types the bundled changelog uses, so one screen renders both. */
fun FeedRelease.toRelease(): Release = Release(
    version = version,
    date = date,
    headline = headline,
    changes = changes.mapNotNull { it.toChange() },
)

fun FeedChange.toChange(): Change? {
    val text = text.trim()
    if (text.isEmpty()) return null
    val kind = when (kind.trim().uppercase()) {
        "NEW" -> ChangeKind.NEW
        "IMPROVED" -> ChangeKind.IMPROVED
        "FIXED" -> ChangeKind.FIXED
        else -> return null
    }
    return Change(kind, text)
}
