package com.animo.app.ui.changelog

import com.animo.app.BuildConfig

/**
 * In-app release notes.
 *
 * This is **user-facing copy only** — what a viewer notices when they open the app. Nothing about
 * endpoints, request budgets, headers, keys, migrations or internal architecture belongs here; those
 * live in `research/` and in the commit log. Keep each line short enough to read on one phone line
 * and write it from the user's side of the screen ("Filters run on the device"), never from ours
 * ("FilterSql builds a raw query").
 *
 * Ordering: newest first. [releases.first] is the version the About screen advertises.
 */
enum class ChangeKind { NEW, IMPROVED, FIXED }

data class Change(val kind: ChangeKind, val text: String)

data class Release(
    val version: String,
    val date: String,
    val headline: String,
    val changes: List<Change>,
)

object Changelog {

    val releases: List<Release> = listOf(
        Release(
            version = "1.3.0",
            date = "17 Nov 2026",
            headline = "Home rebuilt around shelves, and a warmer, easier-to-read look.",
            changes = listOf(
                Change(ChangeKind.NEW, "Home is now shelves — continue watching, new Hindi dubs, recently updated and top rated"),
                Change(ChangeKind.NEW, "Hindi titles have their own row, so the English catalogue is no longer buried"),
                Change(ChangeKind.NEW, "A Hindi tag on every Hindi poster, and one tap to browse only Hindi"),
                Change(ChangeKind.IMPROVED, "Titles now sit under the poster instead of on top of the artwork, so long names are readable"),
                Change(ChangeKind.IMPROVED, "A warmer light theme, with text that stays readable over every poster"),
                Change(ChangeKind.IMPROVED, "The home banner blends into the page and shows how much of the catalogue is Hindi"),
                Change(ChangeKind.IMPROVED, "Genre shortcuts in the drawer are now the popular ones instead of odd stray names"),
                Change(ChangeKind.FIXED, "Thousands of Hindi titles had no artwork — most of them now show their poster"),
            ),
        ),
        Release(
            version = "1.2.0",
            date = "17 Nov 2026",
            headline = "Hindi dub is here: 2,900+ titles, playing inside the app.",
            changes = listOf(
                Change(ChangeKind.NEW, "Hindi dub catalogue — 2,900+ series and movies, synced to your device"),
                Change(ChangeKind.NEW, "\"Hindi dub only\" filter, and a Hindi shortcut in the side drawer"),
                Change(ChangeKind.NEW, "Hindi titles play in the same player, with the same resume and history"),
                Change(ChangeKind.IMPROVED, "Sync Hindi from Settings shows progress page by page"),
                Change(ChangeKind.IMPROVED, "Episode lists for Hindi titles open instantly instead of resolving the whole season first"),
                Change(ChangeKind.FIXED, "Hindi streams that used to fail to load on some networks now play"),
            ),
        ),
        Release(
            version = "1.1.0",
            date = "17 Nov 2026",
            headline = "A full navigation overhaul: browse, search and filter in one place.",
            changes = listOf(
                Change(ChangeKind.NEW, "Bottom navigation — Home, Browse, Library and Settings, one tap apart"),
                Change(ChangeKind.NEW, "A side drawer with your catalogue stats, genre shortcuts and a one-tap sync"),
                Change(ChangeKind.NEW, "Search and filters together: genre, year, type, status, audio and A–Z"),
                Change(ChangeKind.NEW, "Right-edge A–Z rail — tap a letter, or drag down the list to scrub"),
                Change(ChangeKind.NEW, "Continue watching moved into Library, next to your full watch history"),
                Change(ChangeKind.NEW, "Light and dark themes, or follow your phone"),
                Change(ChangeKind.IMPROVED, "Filtering is instant — it runs on your device, no waiting on the network"),
                Change(ChangeKind.IMPROVED, "New Dr. Animo look, from the launcher icon to the home banner"),
                Change(ChangeKind.IMPROVED, "Home shows the whole catalogue count and a cleaner header"),
                Change(ChangeKind.FIXED, "Fixed a startup crash that could hit right after installing"),
                Change(ChangeKind.FIXED, "Fixed episodes that refused to play on some networks"),
            ),
        ),
        Release(
            version = "1.0.0",
            date = "16 Nov 2026",
            headline = "The first release: 9,400+ series, stored on your device.",
            changes = listOf(
                Change(ChangeKind.NEW, "Browse 9,400+ series with the catalogue saved offline"),
                Change(ChangeKind.NEW, "Episode lists with SUB and DUB audio where available"),
                Change(ChangeKind.NEW, "Built-in player with quality switching and resume"),
                Change(ChangeKind.NEW, "Search by title, romaji or native name"),
            ),
        ),
    )

    /** The release the About screen advertises — the one this build ships with. */
    val current: Release = releases.first()

    /**
     * True when this build's changelog has not been opened yet, i.e. the About row should show a
     * "New" tag. First install (`seen == null`) counts as unseen: the user has genuinely never read
     * these notes.
     */
    fun isUnseen(seen: String?): Boolean = seen != BuildConfig.VERSION_NAME

    /** Version to persist once the notes have been opened. */
    val seenMarker: String get() = BuildConfig.VERSION_NAME
}
