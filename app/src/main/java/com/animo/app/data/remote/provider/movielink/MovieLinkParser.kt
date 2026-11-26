package com.animo.app.data.remote.provider.movielink

import com.animo.app.data.remote.Normalize
import com.animo.app.data.remote.dto.AnimoJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * The whole HTML surface of the index, kept free of HTTP so every rule here is unit-testable on the
 * JVM with a hand-written fixture.
 *
 * ## Why there is no DOM parser
 * The pages are server-rendered and shaped like `article > a > img`; the app already reads two other
 * scraped catalogues with regex + `indexOf` scanning (see `RareAnimesParser`), and adding Jsoup for
 * four layouts would be a dependency the other providers cannot share. The rules below avoid
 * backtracking-prone constructs entirely: anchors and elements are found with `indexOf` and their
 * inner HTML is taken by position, so the cost is linear in the document length regardless of how
 * malformed the markup is.
 *
 * ## Why every entry point bounds its input
 * A mirror that suddenly serves a 40 MB page (or an error page echoing the request) must not make
 * the sync allocate endlessly or spend seconds in a scan. [MAX_DOCUMENT_CHARS] is far above the
 * measured ~250–600 KB and far below anything that hurts a phone.
 *
 * ## What this parser deliberately does not do
 * It returns **host-relative** paths (`"movie/<slug>"`, `"/p/<token>"`, poster `src` as printed).
 * The index rotates mirrors, so resolving against a host belongs to [MovieLinkProvider], which is
 * the only class that knows the current index host.
 */
object MovieLinkParser {

    // ------------------------------------------------------------------ listing / search

    /**
     * Cards from a `/page/N` listing or a `/search?q=` result page — the markup is the same: one
     * `<a href="…/movie|series|drama|anime/<slug>">` wrapping an `<img alt="Title (Year) [tags] — …">`.
     *
     * A card that cannot produce a page path and a title is **skipped**, never thrown: one broken
     * tile in a 30-card page is a cosmetic hole, an exception would cost the whole page.
     */
    fun cards(html: String): List<MovieLinkCard> {
        val document = bound(html)
        val cards = LinkedHashMap<String, MovieLinkCard>(CARDS_PER_PAGE_HINT)
        var cursor = 0
        var guard = 0

        while (guard++ < MAX_ANCHORS && cards.size < MAX_CARDS) {
            val open = document.indexOf("<a", cursor, ignoreCase = true)
            if (open < 0) break
            if (!isOpenTag(document, open, "a")) {
                cursor = open + 1
                continue
            }
            val openEnd = document.indexOf('>', open)
            if (openEnd < 0 || openEnd - open > MAX_TAG_CHARS) {
                cursor = open + 1
                continue
            }
            // An anchor cannot nest, so the first `</a>` is this anchor's end. Positional slicing
            // also caps the card body: a page with one unterminated anchor must not turn into a
            // scan of the rest of the document for the *next* card.
            val close = document.indexOf("</a", openEnd, ignoreCase = true)
            if (close < 0) break
            val openTag = document.substring(open, openEnd + 1)
            val bodyEnd = minOf(close, openEnd + 1 + MAX_CARD_BODY)
            val card = card(openTag, document.substring(openEnd + 1, bodyEnd))
            cursor = close + 3
            if (card != null && !cards.containsKey(card.seriesId)) cards[card.seriesId] = card
        }

        return cards.values.toList()
    }

    // ------------------------------------------------------------------ detail

    /**
     * One title page, flattened. [kind] can be supplied by the caller (it comes from the path the
     * card was found on); when it is omitted the path prefix of [seriesId] is used, then the JSON-LD
     * `@type`, and finally `movie` as the dominant case.
     */
    fun detail(html: String, seriesId: String, kind: MovieLinkKind? = null): MovieLinkDetail {
        val document = bound(html)
        val structured = structuredData(document)

        val heading = labelParts(
            firstNonBlank(
                element(document, "h1")?.inner?.let(::plainText),
                metaContent(document, "og:title", "twitter:title"),
                element(document, "title")?.inner?.let(::plainText),
            ).orEmpty(),
        )

        val text = visibleText(document)
        val images = collectImages(document)
        val episodes = episodes(document)

        val title = heading.title.ifBlank { structured?.name.orEmpty() }
        val poster = posterOf(document, images, title)
        val quality = field(text, "Quality").orEmpty()
        val resolution = field(text, "Resolution").orEmpty()

        return MovieLinkDetail(
            seriesId = seriesId,
            kind = kind ?: kindOf(seriesId) ?: kindOf(structured),
            title = title,
            // The `<h1>` is the primary source; the meta row and the JSON-LD are ordered fallbacks
            // because a mirror that restyles the header still keeps at least one of them.
            year = heading.year ?: yearOf(field(text, "Released", "Release Date")) ?: structured?.year,
            rating = structured?.rating ?: ratingOf(field(text, "IMDb", "Rating")),
            poster = poster,
            screenshots = screenshotsOf(images, poster),
            genres = splitList(field(text, "Genres", "Genre"))
                .ifEmpty { structured?.genres.orEmpty() },
            language = field(text, "Language", "Languages", "Audio").orEmpty(),
            quality = quality,
            resolution = resolution,
            released = field(text, "Released", "Release Date", "Release").orEmpty(),
            cast = splitList(field(text, "Cast", "Stars", "Actors"))
                .ifEmpty { structured?.cast.orEmpty() },
            storyline = storyline(document),
            playerUrl = playerPath(document),
            episodes = episodes,
            versions = versionsOf(quality, resolution, episodes),
            structured = structured,
        )
    }

    /** True when the pager rendered a link to `page + 1`; the advertised total is never trusted. */
    fun hasNextPage(html: String, page: Int): Boolean {
        if (page < 1 || page >= MAX_PAGE) return false
        val document = bound(html)
        return NEXT_PAGE.findAll(document).take(MAX_PAGER_LINKS).any { match ->
            match.groupValues[1].toIntOrNull() == page + 1
        }
    }

    // ------------------------------------------------------------------ pieces used by tests

    /** `Title (2023) [Action] — …` → `Title` + `2023` + `[Action]`. */
    fun titleAndYear(raw: String): Pair<String, Int?> {
        val parts = labelParts(raw)
        return parts.title to parts.year
    }

    /** Visible text: script/style bodies dropped, block boundaries turned into line breaks. */
    fun visibleText(html: String): String {
        if (html.isEmpty()) return ""
        val withoutRaw = RAW_BLOCK.replace(COMMENT.replace(html, " "), " ")
        val withBreaks = BLOCK_BREAK.replace(withoutRaw, "\n")
        return lineText(withBreaks)
    }

    /** All JSON-LD payloads, first Movie/TVSeries object wins. `null` when the page has none. */
    fun structuredData(html: String): MovieLinkStructured? {
        val document = bound(html)
        for (block in blocks(document, "script").take(MAX_SCRIPTS)) {
            if (!attr(block.openTag, "type").orEmpty().contains("ld+json", ignoreCase = true)) continue
            val parsed = runCatching { AnimoJson.parseToJsonElement(block.inner.trim()) }.getOrNull()
            val objectValue = firstObject(parsed) ?: continue
            return structuredOf(objectValue)
        }
        return null
    }

    /** The episode grid rows of a series page (empty for films, which have no grid). */
    fun episodes(html: String): List<MovieLinkEpisode> {
        val document = bound(html)
        val region = gridRegion(document) ?: return emptyList()
        val controls = controls(region)
        val seasons = seasonMarkers(region, controls)

        val rows = ArrayList<MovieLinkEpisode>(16)
        var guard = 0

        for (control in controls) {
            if (guard++ > MAX_CONTROLS) break
            val label = plainText(control.inner)
            // A season tab is a control too, and it is not an episode.
            if (isSeasonTab(label)) continue

            val number = episodeNumberOf(label) ?: continue
            val token = tokenOf(control.openTag) ?: continue
            val explicitSeason = intAttr(control.openTag, "data-season")
                ?: intAttr(control.openTag, "data-season-number")

            rows += MovieLinkEpisode(
                number = number,
                // The button's own attribute is the truth; the nearest preceding marker is what
                // carries the season of a grouped grid or of the tab that was rendered.
                season = explicitSeason ?: seasons.lastOrNull { it.first < control.start }?.second,
                label = label,
                quality = qualityToken(label) ?: qualityToken(control.openTag).orEmpty(),
                token = token,
                link = "/p/$token",
            )
        }

        return dedupeEpisodes(rows)
    }

    // ------------------------------------------------------------------ cards

    private fun card(openTag: String, body: String): MovieLinkCard? {
        val href = attr(openTag, "href") ?: return null
        val target = targetOf(href) ?: return null

        val image = firstImage(body)
        val label = firstNonBlank(
            image?.alt,
            attr(openTag, "title"),
            plainText(body),
        ) ?: return null

        val parts = labelParts(label)
        if (parts.title.isBlank()) return null

        return MovieLinkCard(
            seriesId = target.first,
            title = parts.title,
            year = parts.year,
            poster = image?.url.orEmpty(),
            kind = target.second,
            tags = parts.tags,
        )
    }

    private fun targetOf(href: String): Pair<String, MovieLinkKind>? {
        val link = href.trim()
        if (link.isEmpty() || link.startsWith("#") || link.startsWith("javascript", true)) return null
        val match = TARGET_PATH.find(link) ?: return null
        val kind = MovieLinkKind.ofPath(match.groupValues[1]) ?: return null
        // `.html` is never part of a slug; a static-export mirror that appends it must not create a
        // second id for a page the pretty URL already covers.
        val slug = SLUG_EXTENSION.replace(match.groupValues[2], "").trimEnd('/')
        if (slug.isEmpty()) return null
        return "${kind.path}/$slug" to kind
    }

    private data class CardLabel(val title: String, val year: Int?, val tags: List<String>)

    /**
     * The alt text is the only place the card prints both pieces of structured data, so it is parsed
     * once here for listings, detail headings and search results.
     *
     * Order matters: a `(YYYY)` is trusted first (it is unambiguous), then the `[tags]` block, then
     * a trailing ` — <site>` suffix. A title that legitimately contains parentheses keeps them,
     * because only a 4-digit paren is treated as the year.
     */
    private fun labelParts(raw: String): CardLabel {
        val cleaned = WHITESPACE.replace(Normalize.cleanText(raw), " ")
        if (cleaned.isEmpty()) return CardLabel("", null, emptyList())

        val yearMatch = PAREN_YEAR.find(cleaned)
        val year = yearMatch?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in MIN_YEAR..MAX_YEAR }
        val tags = BRACKET_BLOCK.find(cleaned)?.groupValues?.get(1)?.let(::splitList).orEmpty()

        var title = if (year != null && yearMatch != null) {
            cleaned.substring(0, yearMatch.range.first)
        } else {
            withoutSiteSuffix(cleaned)
        }

        title = title
            .trim()
            .trimEnd(' ', '-', ':', '|', '–', '—')
            .trim()
        if (title.isEmpty()) return CardLabel("", year, tags)
        return CardLabel(title, year, tags)
    }

    /**
     * `Title [Uncut] — Index` → `Title [Uncut]`. Only the *last* separator is cut, and only when the
     * tail is short: a title such as "Spider-Man: No Way Home — Extended Cut" would otherwise lose
     * its second half, while the real suffix is always a handful of characters.
     */
    private fun withoutSiteSuffix(raw: String): String {
        val tagStart = raw.indexOf(" [")
        val withoutTags = if (tagStart > 0) raw.substring(0, tagStart) else raw
        val tail = SITE_TAIL.find(withoutTags) ?: return withoutTags
        return if (tail.range.first > 0) withoutTags.substring(0, tail.range.first) else withoutTags
    }

    // ------------------------------------------------------------------ detail fields

    /**
     * Label → value over the visible text.
     *
     * The meta row is the one structure that cannot be sliced by position, because its markup is
     * unknown: it may be a `<p>`, a `<ul>` or a `<div>` per pair. Two independent terminators keep
     * the value from swallowing the rest of the page — the next known label, and the end of the
     * block (block boundaries survive [visibleText] as line breaks). The line break is tried only
     * first when it yields something; layout-per-cell pages put the value on its own line, and a
     * value-less answer there means the line break was in the wrong place.
     */
    private fun field(text: String, vararg labels: String): String? {
        val matched = labels.firstNotNullOfOrNull { label ->
            FIELD_PATTERNS[label]?.find(text)?.let { label to it }
        } ?: return null
        val (label, found) = matched

        val rest = text.substring(found.range.last + 1).take(FIELD_WINDOW)
        val labelCut = FIELD_LABELS
            .asSequence()
            .filter { other -> labels.none { it.equals(other, ignoreCase = true) } }
            .mapNotNull { other -> FIELD_PATTERNS[other]?.find(rest)?.range?.first }
            .minOrNull() ?: rest.length

        val newlineCut = rest.indexOf('\n').takeIf { it >= 0 } ?: rest.length
        val withBreak = cleanValue(rest.substring(0, minOf(newlineCut, labelCut)))
        if (withBreak.isNotEmpty()) return withBreak

        return cleanValue(rest.substring(0, labelCut)).takeIf { it.isNotEmpty() }
    }

    private fun cleanValue(raw: String): String =
        raw.trim().trim(',', '|', '/', ';', '-', '·', '–', '—').trim()

    private fun ratingOf(raw: String?): Double? {
        val value = raw?.let { NUMBER.find(it)?.groupValues?.get(1)?.toDoubleOrNull() } ?: return null
        return value.takeIf { it > 0.0 && it <= 10.0 }
    }

    /**
     * `<h3>Storyline</h3>` followed by a paragraph.
     *
     * The heading is matched by its text rather than by position: the block appears after the meta
     * row and before the download area, and the paragraph is the only prose on the page.
     */
    private fun storyline(document: String): String {
        var cursor = 0
        var guard = 0
        while (guard++ < MAX_HEADINGS) {
            val heading = element(document, "h3", cursor) ?: return ""
            val label = plainText(heading.inner).lowercase().trimEnd(':', ' ')
            if (label in STORYLINE_HEADINGS) {
                val paragraph = element(document, "p", heading.end)
                if (paragraph != null) {
                    val prose = plainText(paragraph.inner)
                    if (prose.isNotEmpty()) return prose
                }
                // No <p>: take the visible text up to the next heading, capped.
                val rest = document.substring(heading.end)
                val next = HEADING_TAG.find(rest)?.range?.first ?: rest.length
                val prose = plainText(rest.substring(0, minOf(next, STORYLINE_WINDOW)))
                if (prose.isNotEmpty()) return prose
            }
            cursor = heading.end
        }
        return ""
    }

    /**
     * The `/p/<token>` of the page's own player — the value that rotates, so it is also the value a
     * caller must re-read at playback time instead of trusting a stored one.
     *
     * Four shapes are recognised in order, because the page has changed layout more than once and a
     * stored link is only as good as the pattern that can refresh it:
     *  1. `<iframe id="mlbdHdFrame" src="…">` — the measured shape;
     *  2. any other iframe whose `src`/`data-src` is a `/p/` path;
     *  3. a `/p/` path in any attribute (`data-player`, `data-embed`, `href`, …);
     *  4. last resort: the first `/p/<token>` literal anywhere in the document (an inline script).
     *
     * Returns `""` when the page carries no player at all.
     */
    fun playerPath(html: String): String {
        val document = bound(html)
        var fallback = ""
        var scanned = 0
        for (match in IFRAME_TAG.findAll(document)) {
            if (scanned++ > MAX_IFRAMES) break
            val tag = match.value
            val source = attr(tag, "src") ?: attr(tag, "data-src") ?: continue
            if (source.isBlank() || source.startsWith("about:", true)) continue
            if (attr(tag, "id").orEmpty().equals(PLAYER_FRAME_ID, ignoreCase = true)) return source
            if (fallback.isEmpty() && source.contains(PLAYER_PATH)) fallback = source
        }
        if (fallback.isNotEmpty()) return fallback

        // The frame may be built by JS instead of printed in markup: accept any attribute that
        // carries the path, then any literal at all.
        PLAYER_ATTR.find(document)?.groupValues?.getOrNull(1)?.let { return it }
        PLAYER_LITERAL.find(document)?.value?.let { return it }
        return ""
    }

    private fun versionsOf(quality: String, resolution: String, episodes: List<MovieLinkEpisode>): List<String> {
        val out = LinkedHashSet<String>(4)
        listOf(quality, resolution).forEach { raw -> qualityToken(raw)?.let(out::add) }
        episodes.forEach { episode -> episode.quality.takeIf { it.isNotEmpty() }?.let(out::add) }
        return out.toList()
    }

    private fun dedupeEpisodes(rows: List<MovieLinkEpisode>): List<MovieLinkEpisode> {
        val byKey = LinkedHashMap<String, MovieLinkEpisode>(rows.size)
        for (row in rows) {
            // A `<li>` wrapping an `<a>` is two controls for one episode; the token is the identity.
            val key = row.token ?: "${row.season ?: 0}-${row.number}"
            if (!byKey.containsKey(key)) byKey[key] = row
        }
        return byKey.values.toList()
    }

    // ------------------------------------------------------------------ episode grid

    /**
     * The grid's HTML. Sliced out first so the label scan can never pick up a nav "1 2 3" pager or a
     * "Episode 5" in a related-titles carousel.
     */
    private fun gridRegion(document: String): String? {
        val marker = GRID_ID.find(document) ?: return null
        val open = document.lastIndexOf('<', marker.range.first)
        if (open < 0) return null
        val tag = nameAt(document, open) ?: return null
        return elementAt(document, open, tag)?.inner
    }

    /**
     * Controls of the grid, in document order.
     *
     * `button`, `a` and `li` are the three shapes a per-episode row has used on similar indexes.
     * No nesting exists among them, so the closing tag is found positionally and the inner HTML is
     * capped — an unbalanced `<button>` must not turn the scan into the whole region.
     */
    private fun controls(region: String): List<Control> {
        val out = ArrayList<Control>(16)
        var scanned = 0
        for (match in CONTROL_TAG.findAll(region)) {
            if (scanned++ > MAX_CONTROLS) break
            val tag = match.groupValues[1].lowercase()
            val innerStart = match.range.last + 1
            val close = region.indexOf("</$tag", innerStart, ignoreCase = true)
            val innerEnd = minOf(
                if (close < 0) region.length else close,
                innerStart + MAX_CONTROL_BODY,
            )
            out += Control(match.range.first, match.value, region.substring(innerStart, innerEnd))
        }
        return out
    }

    /**
     * Season markers: every `data-season="N"` in the grid, by position.
     *
     * Two layouts have to be covered by one rule. A grouped grid puts the attribute on the wrapper
     * `div` of each season; a tabbed grid puts it on the tab buttons, and only the first tab is the
     * rendered state — the others are switches that need a click. A run of markers with **no
     * episode between them** is therefore collapsed to its first member, which turns "tabs 1, 2, 3"
     * into "season 1" while leaving "group 1 … group 2" intact.
     */
    private fun seasonMarkers(region: String, controls: List<Control>): List<Pair<Int, Int>> {
        val markers = ArrayList<Pair<Int, Int>>(8)
        for (match in SEASON_ATTR.findAll(region)) {
            if (markers.size >= MAX_SEASONS) break
            val season = match.groupValues[1].toIntOrNull() ?: continue
            markers += match.range.first to season
        }

        val collapsed = ArrayList<Pair<Int, Int>>(markers.size)
        for (marker in markers) {
            val previous = collapsed.lastOrNull()
            if (previous == null) {
                collapsed += marker
                continue
            }
            val episodeBetween = controls.any { control ->
                control.start > previous.first && control.start < marker.first &&
                    episodeNumberOf(plainText(control.inner)) != null
            }
            if (episodeBetween) collapsed += marker
        }
        return collapsed
    }

    private fun isSeasonTab(label: String): Boolean =
        episodeNumberOf(label) == null &&
            label.toIntOrNull() == null &&
            SEASON_LABEL.containsMatchIn(label)

    private fun episodeNumberOf(label: String): Int? =
        EPISODE_WORD.find(label)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }
            ?: BARE_EPISODE.find(label)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }

    /**
     * The `/p/<token>` behind one control.
     *
     * A button may carry the player URL (`href`/`data-href`/`data-src`), the bare token
     * (`data-token`/`data-id`), or only an inline handler (`play('…')` / `onclick="/p/…"`). All three
     * are read because the grid markup is generated by the theme and has changed between mirrors.
     * A control with none of them is skipped: a fabricated token would resolve to a dead player.
     */
    private fun tokenOf(openTag: String): String? {
        val url = attr(openTag, "data-href")
            ?: attr(openTag, "href")
            ?: attr(openTag, "data-src")
            ?: attr(openTag, "data-url")
            ?: attr(openTag, "data-link")
            ?: attr(openTag, "data-file")

        if (url != null) {
            PATH_TOKEN.find(url)?.let { return it.groupValues[1] }
            if (url.startsWith("$PLAYER_PATH/")) {
                return url.removePrefix("$PLAYER_PATH/").trimEnd('/').takeIf { TOKEN.matches(it) }
            }
        }

        PATH_TOKEN.find(openTag)?.let { return it.groupValues[1] }
        PLAY_CALL.find(openTag)?.let { return it.groupValues[1] }
        return attr(openTag, "data-token")?.takeIf { TOKEN.matches(it) }
    }

    // ------------------------------------------------------------------ images

    private fun posterOf(document: String, images: List<ImageTag>, title: String): String {
        metaContent(document, "og:image", "twitter:image")?.takeIf(::isUsableImage)?.let { return it }
        images.firstOrNull { it.alt.isNotBlank() && title.isNotBlank() && it.alt.contains(title, true) }
            ?.url
            ?.takeIf(::isUsableImage)
            ?.let { return it }
        return images.firstOrNull { it.url.contains("/upload", ignoreCase = true) }
            ?.url
            ?.takeIf(::isUsableImage)
            .orEmpty()
    }

    private fun screenshotsOf(images: List<ImageTag>, poster: String): List<String> =
        images
            .map { it.url }
            .filter { it != poster && isUsableImage(it) }
            .distinct()
            .take(MAX_SCREENSHOTS)

    private fun collectImages(document: String): List<ImageTag> {
        val out = ArrayList<ImageTag>(8)
        var cursor = 0
        var scanned = 0
        while (scanned++ < MAX_IMAGES) {
            val open = document.indexOf("<img", cursor, ignoreCase = true)
            if (open < 0) break
            val end = document.indexOf('>', open)
            if (end < 0 || end - open > MAX_TAG_CHARS) break
            val tag = document.substring(open, end + 1)
            val url = attr(tag, "data-src") ?: attr(tag, "data-lazy-src") ?: attr(tag, "src").orEmpty()
            if (url.isNotBlank()) out += ImageTag(url = url, alt = attr(tag, "alt").orEmpty())
            cursor = end + 1
        }
        return out
    }

    private fun firstImage(body: String): ImageTag? {
        val open = body.indexOf("<img", ignoreCase = true)
        if (open < 0) return null
        val end = body.indexOf('>', open)
        if (end < 0 || end - open > MAX_TAG_CHARS) return null
        val tag = body.substring(open, end + 1)
        val url = attr(tag, "data-src") ?: attr(tag, "data-lazy-src") ?: attr(tag, "src").orEmpty()
        val alt = attr(tag, "alt").orEmpty()
        if (url.isBlank() && alt.isBlank()) return null
        return ImageTag(url, alt)
    }

    private fun isUsableImage(url: String): Boolean {
        if (url.isBlank() || url.startsWith("data:", ignoreCase = true)) return false
        if (url.endsWith(".svg", ignoreCase = true)) return false
        return NOISE_IMAGE.none { url.contains(it, ignoreCase = true) }
    }

    // ------------------------------------------------------------------ JSON-LD

    private fun structuredOf(obj: JsonObject): MovieLinkStructured = MovieLinkStructured(
        type = text(obj, "@type").orEmpty(),
        name = text(obj, "name").orEmpty(),
        description = text(obj, "description").orEmpty(),
        year = yearOf(text(obj, "datePublished")) ?: intValue(obj["copyrightYear"]),
        genres = strings(obj["genre"]),
        rating = (obj["aggregateRating"] as? JsonObject)?.let { numberValue(it["ratingValue"]) },
        poster = imageUrl(obj["image"]),
        cast = strings(obj["actor"]),
        episodeCount = intValue(obj["numberOfEpisodes"]),
    )

    /**
     * `genre` is a string on film pages and an array on series pages; `actor` is always objects.
     * One helper covers both so a shape change cannot silently empty the field.
     */
    private fun strings(value: JsonElement?): List<String> = when (value) {
        is JsonArray -> value.mapNotNull { item ->
            when (item) {
                is JsonPrimitive -> item.contentOrNull
                is JsonObject -> text(item, "name")
                else -> null
            }
        }
        is JsonPrimitive -> listOfNotNull(value.contentOrNull)
        is JsonObject -> listOfNotNull(text(value, "name"))
        else -> emptyList()
    }.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(MAX_LIST)

    private fun text(obj: JsonObject, key: String): String? =
        (obj[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    /** Numbers arrive as `7.4` on some pages and `"7.4"` on others. */
    private fun intValue(value: JsonElement?): Int? =
        (value as? JsonPrimitive)?.let { it.intOrNull ?: it.doubleOrNull?.toInt() }

    private fun numberValue(value: JsonElement?): Double? =
        (value as? JsonPrimitive)?.let { it.doubleOrNull ?: it.intOrNull?.toDouble() }
            ?.takeIf { it > 0.0 && it <= 10.0 }

    private fun imageUrl(value: JsonElement?): String = when (value) {
        is JsonPrimitive -> value.contentOrNull.orEmpty()
        is JsonObject -> text(value, "url").orEmpty()
        is JsonArray -> value.firstNotNullOfOrNull { imageUrl(it).takeIf { url -> url.isNotEmpty() } }.orEmpty()
        else -> ""
    }

    private fun firstObject(element: JsonElement?): JsonObject? = when (element) {
        is JsonObject -> {
            val graph = element["@graph"]
            if (graph is JsonArray) graph.firstNotNullOfOrNull { it as? JsonObject } else element
        }
        is JsonArray -> element.firstNotNullOfOrNull { it as? JsonObject }
        else -> null
    }

    // ------------------------------------------------------------------ element scanning

    private class Element(val openTag: String, val inner: String, val end: Int)

    private class Block(val openTag: String, val inner: String)

    private class Control(val start: Int, val openTag: String, val inner: String)

    private class ImageTag(val url: String, val alt: String)

    /**
     * The first `<tag …>…</tag>` at or after [from], nesting-aware.
     *
     * Nesting matters for exactly one element this parser cares about: the episode grid is a `div`
     * that contains other `div`s, so a naive `indexOf("</div>")` would cut it at the first inner
     * close. Depth is counted with the same linear scan, and both loops are guarded.
     */
    private fun element(document: String, tag: String, from: Int = 0): Element? {
        var cursor = from
        var guard = 0
        while (guard++ < MAX_ELEMENT_LOOKUPS) {
            val open = document.indexOf("<$tag", cursor, ignoreCase = true)
            if (open < 0) return null
            if (isOpenTag(document, open, tag)) return elementAt(document, open, tag)
            cursor = open + 1
        }
        return null
    }

    /** [element] for a known opening index (the grid's own tag). */
    private fun elementAt(document: String, open: Int, tag: String): Element? {
        val openEnd = document.indexOf('>', open)
        if (openEnd < 0 || openEnd - open > MAX_TAG_CHARS) return null
        val openTag = document.substring(open, openEnd + 1)
        if (openTag.endsWith("/>")) return Element(openTag, "", openEnd + 1)

        var depth = 1
        var cursor = openEnd + 1
        var guard = 0
        while (guard++ < MAX_ELEMENT_WALK) {
            val nextOpen = indexOfOpenTag(document, tag, cursor)
            val close = document.indexOf("</$tag", cursor, ignoreCase = true)
            if (close < 0) return null
            if (nextOpen >= 0 && nextOpen < close) {
                depth += 1
                cursor = nextOpen + 1
            } else {
                depth -= 1
                val closeEnd = document.indexOf('>', close)
                if (closeEnd < 0) return null
                if (depth == 0) {
                    return Element(openTag, document.substring(openEnd + 1, close), closeEnd + 1)
                }
                cursor = closeEnd + 1
            }
        }
        return null
    }

    private fun indexOfOpenTag(document: String, tag: String, from: Int): Int {
        var cursor = from
        var guard = 0
        while (guard++ < MAX_ELEMENT_LOOKUPS) {
            val found = document.indexOf("<$tag", cursor, ignoreCase = true)
            if (found < 0) return -1
            if (isOpenTag(document, found, tag)) return found
            cursor = found + 1
        }
        return -1
    }

    /** `<script>` bodies are taken flat: the parser never nests them, and one close tag is exact. */
    private fun blocks(document: String, tag: String): List<Block> {
        val out = ArrayList<Block>(4)
        var cursor = 0
        var guard = 0
        while (guard++ < MAX_BLOCKS) {
            val open = document.indexOf("<$tag", cursor, ignoreCase = true)
            if (open < 0) break
            if (!isOpenTag(document, open, tag)) {
                cursor = open + 1
                continue
            }
            val openEnd = document.indexOf('>', open)
            if (openEnd < 0 || openEnd - open > MAX_TAG_CHARS) break
            val close = document.indexOf("</$tag", openEnd, ignoreCase = true)
            if (close < 0) break
            val closeEnd = document.indexOf('>', close)
            if (closeEnd < 0) break
            out += Block(document.substring(open, openEnd + 1), document.substring(openEnd + 1, close))
            cursor = closeEnd + 1
        }
        return out
    }

    private fun isOpenTag(document: String, open: Int, tag: String): Boolean {
        val nameEnd = open + 1 + tag.length
        if (nameEnd >= document.length) return false
        val next = document[nameEnd]
        return next == '>' || next == '/' || next.isWhitespace()
    }

    private fun nameAt(document: String, open: Int): String? {
        var cursor = open + 1
        val start = cursor
        while (cursor < document.length && cursor - start < MAX_TAG_NAME && document[cursor].isLetterOrDigit()) {
            cursor += 1
        }
        return if (cursor > start) document.substring(start, cursor).lowercase() else null
    }

    // ------------------------------------------------------------------ attributes

    private fun attr(tag: String, name: String): String? {
        val pattern = ATTRS[name] ?: return null
        val match = pattern.find(tag) ?: return null
        val raw = match.groups[1]?.value ?: match.groups[2]?.value ?: match.groups[3]?.value
        return raw?.let { Normalize.cleanText(it) }?.takeIf { it.isNotEmpty() }
    }

    private fun intAttr(tag: String, name: String): Int? = attr(tag, name)?.toIntOrNull()

    private fun metaContent(document: String, vararg names: String): String? {
        var scanned = 0
        for (match in META_TAG.findAll(document)) {
            if (scanned++ > MAX_META) break
            val tag = match.value
            val key = attr(tag, "property") ?: attr(tag, "name") ?: continue
            if (names.none { it.equals(key, ignoreCase = true) }) continue
            attr(tag, "content")?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }

    // ------------------------------------------------------------------ text helpers

    private fun bound(html: String): String =
        if (html.length <= MAX_DOCUMENT_CHARS) html else html.substring(0, MAX_DOCUMENT_CHARS)

    private fun plainText(raw: String): String = WHITESPACE.replace(
        TAG.replace(Normalize.unescapeHtml(raw), " ").replace('\u00A0', ' '),
        " ",
    ).trim()

    /** Like [plainText] but keeps the block line breaks [visibleText] inserted. */
    private fun lineText(raw: String): String {
        val stripped = TAG.replace(Normalize.unescapeHtml(raw), " ").replace('\u00A0', ' ')
        return stripped.lines()
            .map { WHITESPACE.replace(it, " ").trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }

    private fun splitList(raw: String?): List<String> =
        raw.orEmpty()
            .split(',', '/', '|', ';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(MAX_LIST)

    private fun qualityToken(raw: String?): String? {
        if (raw.isNullOrEmpty()) return null
        val match = QUALITY_TOKEN.find(raw) ?: return null
        return match.groupValues[1] + "p"
    }

    private fun kindOf(seriesId: String): MovieLinkKind? =
        MovieLinkKind.ofPath(seriesId.substringBefore('/'))

    /** `TVSeries`/`TVEpisode`/`Series` mean the page is episodic; anything else is a film. */
    private fun kindOf(structured: MovieLinkStructured?): MovieLinkKind {
        val type = structured?.type.orEmpty().lowercase()
        return if (type.contains("series") || type.contains("episode")) MovieLinkKind.SERIES else MovieLinkKind.MOVIE
    }

    private fun firstNonBlank(vararg values: String?): String? =
        values.firstOrNull { !it.isNullOrBlank() }?.trim()

    private fun yearOf(raw: String?): Int? =
        raw?.let { YEAR_IN_TEXT.find(it)?.groupValues?.get(1)?.toIntOrNull() }
            ?.takeIf { it in MIN_YEAR..MAX_YEAR }

    // ------------------------------------------------------------------ constants

    private const val MIN_YEAR = 1900
    private const val MAX_YEAR = 2100

    /** The index advertises 529 pages; anything past it is a pager artefact, not a next page. */
    const val MAX_PAGE = 529

    private const val CARDS_PER_PAGE_HINT = 32

    /** Far above the measured 250–600 KB and far below anything that hurts a phone. */
    private const val MAX_DOCUMENT_CHARS = 4_000_000
    private const val MAX_TAG_CHARS = 4_096
    private const val MAX_TAG_NAME = 24
    private const val MAX_CARD_BODY = 6_000
    private const val MAX_CARDS = 600
    private const val MAX_ANCHORS = 4_000
    private const val MAX_CONTROLS = 700
    private const val MAX_CONTROL_BODY = 600
    private const val MAX_ELEMENT_LOOKUPS = 20_000
    private const val MAX_ELEMENT_WALK = 20_000
    private const val MAX_BLOCKS = 40
    private const val MAX_SCRIPTS = 40
    private const val MAX_IMAGES = 200
    private const val MAX_IFRAMES = 60
    private const val MAX_HEADINGS = 60
    private const val MAX_META = 200
    private const val MAX_SCREENSHOTS = 8
    private const val MAX_LIST = 24
    private const val MAX_PAGER_LINKS = 64
    private const val MAX_SEASONS = 24
    private const val FIELD_WINDOW = 240
    private const val STORYLINE_WINDOW = 2_000

    /** DOM ids from the measured page; markup identifiers, not provider hosts. */
    private const val PLAYER_FRAME_ID = "mlbdHdFrame"
    private const val PLAYER_PATH = "/p"

    private val WHITESPACE = Regex("\\s+")
    private val TAG = Regex("<[^>]{0,4096}>")
    private val COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)

    /**
     * Script/style bodies carry no prose but do carry the words "Genre" and "Cast" in theme JS, so
     * they are removed before any label scan.
     */
    private val RAW_BLOCK = Regex(
        "<(script|style|noscript|template)\\b[^>]{0,2048}>.*?</\\1\\s*>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    /** Block boundaries are the field terminator that survives [plainText]'s whitespace collapse. */
    private val BLOCK_BREAK = Regex(
        "</?(?:p|div|li|tr|td|th|h[1-6]|br|section|article|ul|ol|dl|dt|dd)\\b[^>]{0,2048}>",
        RegexOption.IGNORE_CASE,
    )

    private val HEADING_TAG = Regex("<h[1-6]\\b", RegexOption.IGNORE_CASE)

    private val PAREN_YEAR = Regex("\\(\\s*(\\d{4})\\s*\\)")
    private val YEAR_IN_TEXT = Regex("\\b(\\d{4})\\b")
    private val NUMBER = Regex("(\\d{1,2}(?:\\.\\d{1,2})?)")
    private val BRACKET_BLOCK = Regex("\\[([^\\[\\]]{0,200})\\]")
    private val SITE_TAIL = Regex("\\s+[—–-]\\s+[^—–-]{1,64}$")
    private val SLUG_EXTENSION = Regex("\\.[A-Za-z]{2,5}$")

    private val TARGET_PATH = Regex(
        "(?:^|/)(movie|series|drama|anime)/([A-Za-z0-9][A-Za-z0-9._~%+-]{0,200})",
        RegexOption.IGNORE_CASE,
    )

    private val GRID_ID = Regex("id\\s*=\\s*[\"']mlbdEpisodeDownloads[\"']", RegexOption.IGNORE_CASE)
    private val IFRAME_TAG = Regex("<iframe\\b[^>]{0,2048}>", RegexOption.IGNORE_CASE)
    private val META_TAG = Regex("<meta\\b[^>]{0,2048}>", RegexOption.IGNORE_CASE)
    private val CONTROL_TAG = Regex("<(button|a|li)\\b[^>]{0,1500}>", RegexOption.IGNORE_CASE)
    private val NEXT_PAGE = Regex(
        "href\\s*=\\s*[\"'][^\"']{0,300}/page/(\\d{1,5})/?[\"'#?]",
        RegexOption.IGNORE_CASE,
    )

    private val EPISODE_WORD = Regex("^\\s*(?:episode|ep|e)\\s*\\.?\\s*(\\d{1,4})\\b", RegexOption.IGNORE_CASE)
    private val BARE_EPISODE = Regex("^\\s*(\\d{1,4})\\s*$")
    private val SEASON_LABEL = Regex("season\\s*(\\d{1,2})\\b", RegexOption.IGNORE_CASE)
    private val SEASON_ATTR = Regex("data-season(?:-number)?\\s*=\\s*[\"']?(\\d{1,2})", RegexOption.IGNORE_CASE)
    private val QUALITY_TOKEN = Regex("\\b(360|480|540|576|720|1080|1440|2160)\\s*p\\b", RegexOption.IGNORE_CASE)

    private val PATH_TOKEN = Regex("/p/([A-Za-z0-9_-]{2,128})")

    /**
     * Fallbacks for the two layouts that do not print an iframe: a path handed to a JS player through
     * an attribute, or a bare literal in an inline script. Kept separate from [PATH_TOKEN], which has
     * no attribute context and is used when a control carries a token rather than a path.
     */
    private val PLAYER_ATTR = Regex(
        "\\b(?:href|src|data-src|data-player|data-embed|data-url|data-href)\\s*=\\s*[\"'](/p/[A-Za-z0-9_-]{2,128})[\"']",
        RegexOption.IGNORE_CASE,
    )
    private val PLAYER_LITERAL = Regex("/p/[A-Za-z0-9_-]{20,128}")
    private val PLAY_CALL = Regex(
        "\\bplay(?:Episode|Video|Movie)?\\s*\\(\\s*[\"']([A-Za-z0-9_-]{2,128})[\"']",
        RegexOption.IGNORE_CASE,
    )
    private val TOKEN = Regex("[A-Za-z0-9_-]{2,128}")

    private val STORYLINE_HEADINGS = setOf("storyline", "synopsis", "plot", "story", "summary")

    private val NOISE_IMAGE = listOf("logo", "avatar", "sprite", "emoji", "1x1", "placeholder", "favicon")

    /** Labels the meta row uses; the set doubles as the terminator list for [field]. */
    private val FIELD_LABELS = listOf(
        "Genres", "Genre", "Language", "Languages", "Audio", "Quality", "Resolution",
        "Released", "Release Date", "Release", "Cast", "Stars", "Actors", "IMDb", "Rating",
        "Director", "Country", "Runtime", "Duration", "Size", "Subtitles", "Subtitle",
        "Format", "Network", "Status", "Aired",
    )

    private val FIELD_PATTERNS: Map<String, Regex> = FIELD_LABELS.associateWith { label ->
        Regex("(?<![A-Za-z])${Regex.escape(label)}\\s*:", RegexOption.IGNORE_CASE)
    }

    private val ATTR_NAMES = listOf(
        "href", "src", "data-src", "data-lazy-src", "alt", "title", "content", "property", "name",
        "id", "class", "type", "data-href", "data-url", "data-link", "data-file", "data-token",
        "data-id", "data-season", "data-season-number", "onclick",
    )

    private val ATTRS: Map<String, Regex> = ATTR_NAMES.associateWith { name ->
        // Leading `(?:^|\s)` keeps `src` from matching inside `data-src`; group 3 accepts the
        // unquoted values that appear in hand-written inline handlers.
        Regex(
            "(?:^|\\s)${Regex.escape(name)}\\s*=\\s*(?:\"([^\"]{0,2048})\"|'([^']{0,2048})'|([^\\s\"'>]{1,2048}))",
            RegexOption.IGNORE_CASE,
        )
    }
}
