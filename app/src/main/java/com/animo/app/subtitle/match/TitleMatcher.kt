package com.animo.app.subtitle.match

import kotlin.math.max
import kotlin.math.min

/** What the app knows about the thing being watched, in matching terms. */
data class MatchTarget(
    val titles: List<String>,
    /** Explicit season, when the caller knows it; otherwise it is read from [titles]. */
    val season: Int? = null,
    val episode: Int? = null,
    val year: Int? = null,
)

/** One release name offered by a subtitle site. */
data class MatchCandidate(
    val title: String,
    val year: Int? = null,
)

enum class MatchConfidence { EXACT, STRONG, WEAK, NONE }

/** Every ingredient that went into the score, so a bad match can be explained instead of argued. */
data class MatchSignals(
    val normalizedEqual: Boolean = false,
    val coverage: Double = 0.0,
    val anchor: Double = 0.0,
    val subset: Boolean = false,
    val acronym: Boolean = false,
    /** `null` = not comparable (one side does not say). */
    val season: Boolean? = null,
    val episode: Boolean? = null,
    val year: Boolean? = null,
)

data class TitleMatch(
    val candidate: MatchCandidate,
    val release: ReleaseTitle,
    val score: Double,
    val confidence: MatchConfidence,
    val signals: MatchSignals,
    val reasons: List<String>,
    val rejected: Boolean = false,
) {
    val accepted: Boolean get() = !rejected && confidence != MatchConfidence.NONE
}

/**
 * Release-name ↔ title matcher.
 *
 * Free subtitle sites index *release names*: `Frieren.Beyond.Journeys.End.S01E05.JAPANESE.WEBRip`.
 * A plain token comparison is not enough — it cannot tell "Pokémon S16E05" from "Pokémon Season 9",
 * it drowns in quality words, and it misses "AoT" for "Attack on Titan". So four things happen:
 *
 *  1. **Numbers are checked first.** A season or episode contradiction is a hard reject, regardless
 *     of how similar the words are. This is the rule that kills the most wrong matches.
 *  2. **Words are weighted.** Content words count; release noise ("1080p", "WEB-DL", "japanese")
 *     counts for almost nothing. A hit on the franchise name is worth more than a tail word.
 *  3. **Near-misses are allowed.** Plural/singular, a one- or two-letter typo and a shared prefix
 *     all count, because sites spell the same show three different ways.
 *  4. **The result is explained.** [TitleMatch.signals] plus [TitleMatch.reasons] say why a release
 *     was accepted or dropped, which is what makes a bad match debuggable in the field.
 *
 * Stateless and thread-safe; normalisation is cached underneath.
 */
object TitleMatcher {

    private const val STRONG_THRESHOLD = 0.72
    private const val WEAK_THRESHOLD = 0.42

    fun match(target: MatchTarget, candidate: MatchCandidate): TitleMatch {
        val release = ReleaseTitle.parse(candidate.title)
        val titles = target.titles
            .map(TitleNormalizer::normalize)
            .filterNot { it.isEmpty }

        if (titles.isEmpty()) {
            return TitleMatch(
                candidate = candidate,
                release = release,
                score = 0.0,
                confidence = MatchConfidence.NONE,
                signals = MatchSignals(),
                reasons = listOf("no title to compare"),
                rejected = true,
            )
        }

        val seasonWanted = target.season ?: ReleaseTitle.seasonInTitle(target.titles)
        val seasonAgrees = when {
            seasonWanted == null || release.season == null -> null
            else -> seasonWanted == release.season
        }
        val episodeAgrees = when {
            target.episode == null || release.episodes == null -> null
            else -> release.coversEpisode(target.episode)
        }
        val yearWanted = target.year ?: candidate.year
        val yearAgrees = when {
            yearWanted == null || release.year == null -> null
            else -> yearWanted == release.year
        }

        val reasons = mutableListOf<String>()

        // ---- hard contradictions: no amount of word similarity saves these
        if (seasonAgrees == false) {
            reasons += "season ${release.season} ≠ $seasonWanted"
            return rejected(candidate, release, reasons)
        }
        if (episodeAgrees == false) {
            reasons += "episode ${target.episode} outside ${release.episodes}"
            return rejected(candidate, release, reasons)
        }

        val best = titles.mapNotNull { title -> lexical(title, release) }
            .maxByOrNull { it.base }
            ?: return rejected(candidate, release, listOf("nothing in common"))

        if (target.episode != null && release.episodes == null) {
            reasons += "no episode ${target.episode} in this name"
            return rejected(candidate, release, reasons)
        }
        if (!best.equal && !best.acronym && !best.anchorMatched && best.coverage < 0.6) {
            reasons += "franchise name missing (${best.matched.joinToString(", ").ifBlank { "no words" }})"
            return rejected(candidate, release, reasons)
        }

        var score = best.base
        when (seasonAgrees) {
            true -> { score += 0.05; reasons += "season ${release.season} matches" }
            null -> score += 0.02
            false -> Unit
        }
        when (episodeAgrees) {
            true -> { score += 0.05; reasons += "episode ${target.episode} inside ${release.episodes}" }
            null -> score += 0.02
            false -> Unit
        }
        when (yearAgrees) {
            true -> { score += 0.03; reasons += "year ${release.year} matches" }
            false -> { score -= 0.04; reasons += "year ${release.year} ≠ $yearWanted" }
            null -> Unit
        }
        score = score.coerceIn(0.0, 1.0)

        if (best.equal) reasons += "same title after normalisation"
        if (best.subset) reasons += "release name only adds noise"
        if (best.acronym) reasons += "acronym match"
        if (best.matched.isNotEmpty()) reasons += "shared: ${best.matched.joinToString(", ")}"

        val confidence = when {
            best.equal -> MatchConfidence.EXACT
            score >= STRONG_THRESHOLD -> MatchConfidence.STRONG
            score >= WEAK_THRESHOLD -> MatchConfidence.WEAK
            else -> MatchConfidence.NONE
        }

        return TitleMatch(
            candidate = candidate,
            release = release,
            score = score,
            confidence = confidence,
            signals = MatchSignals(
                normalizedEqual = best.equal,
                coverage = best.coverage,
                anchor = best.anchor,
                subset = best.subset,
                acronym = best.acronym,
                season = seasonAgrees,
                episode = episodeAgrees,
                year = yearAgrees,
            ),
            reasons = reasons,
        )
    }

    /** Every candidate, best first. Rejected matches sink to the bottom but stay inspectable. */
    fun rank(target: MatchTarget, candidates: List<MatchCandidate>): List<TitleMatch> =
        candidates.map { match(target, it) }.sortedWith(
            compareByDescending<TitleMatch> { it.accepted }
                .thenByDescending { it.score },
        )

    /** Only the candidates that pass. */
    fun accepted(target: MatchTarget, candidates: List<MatchCandidate>): List<TitleMatch> =
        rank(target, candidates).filter { it.accepted }

    // ---------------------------------------------------------------- lexical layer

    private class Lexical(
        val coverage: Double,
        val anchor: Double,
        val subset: Boolean,
        val acronym: Boolean,
        val equal: Boolean,
        val matched: List<String>,
        /** Did the franchise name (the first real word of the title) appear in the release? */
        val anchorMatched: Boolean,
    ) {
        val base: Double
            get() {
                if (equal) return 1.0
                var value = 0.55 * coverage + 0.35 * anchor
                if (subset) value += 0.04
                if (acronym) value += 0.06
                return value.coerceIn(0.0, 1.0)
            }
    }

    private fun lexical(target: NormalizedTitle, release: ReleaseTitle): Lexical? {
        val releaseTokens = release.normalized.tokens
        if (releaseTokens.isEmpty()) return null

        val distinctive = target.tokens.filter { it.length > 1 && it !in TitleNormalizer.GENERIC }
        val effective = distinctive.ifEmpty { target.tokens }
        if (effective.isEmpty()) return null

        val weightPerToken = if (distinctive.isEmpty()) 0.15 else 1.0
        var matchedWeight = 0.0
        val matched = mutableListOf<String>()
        var matchedLongest = 0

        for (token in effective) {
            val hit = releaseTokens.firstOrNull { it == token }
                ?: releaseTokens.firstOrNull { tokensMatch(it, token) }
            if (hit != null) {
                matchedWeight += weightPerToken
                matched += token
                matchedLongest = max(matchedLongest, token.length)
            }
        }

        val plainCoverage = matchedWeight / (effective.size * weightPerToken)
        val longestTarget = effective.maxOf { it.length }.coerceAtLeast(1)
        val plainAnchor = if (matched.isEmpty()) 0.0 else matchedLongest.toDouble() / longestTarget
        val subset = matched.size == effective.size && releaseTokens.size >= effective.size

        // Acronyms are computed from the *full* title: "Attack on Titan" → AoT only works if "on"
        // is still there, even though it is a stop word for everything else.
        val acronym = acronymMatch(target.tokens, releaseTokens) ||
            acronymMatch(releaseTokens, target.tokens)

        // An acronym hit is real evidence: raise a weak lexical score to a plausible one, but keep
        // the strong/weak thresholds meaningful by not boosting past 0.65.
        val coverage = if (acronym) max(plainCoverage, 0.65) else plainCoverage
        val anchor = if (acronym) max(plainAnchor, 0.65) else plainAnchor

        val equal = target.text == release.normalized.text ||
            (target.mainTokens.isNotEmpty() && target.mainTokens == release.normalized.mainTokens)

        // The franchise name is the first word that means something — "pokemon" for
        // "Pokémon Season 9 Battle Frontier", "frieren" for "Frieren: Beyond Journey's End".
        // A release that does not contain it is a different show wearing one shared word
        // ("Shangri-La Frontier" for a Pokémon season, "Mewtwo Returns" for an episode).
        val anchorToken = target.mainTokens.firstOrNull { it.length > 1 && it !in TitleNormalizer.GENERIC }
            ?: target.tokens.firstOrNull { it.length > 1 && it !in TitleNormalizer.GENERIC }
            ?: effective.first()
        val anchorMatched = matched.contains(anchorToken) ||
            releaseTokens.any { it == anchorToken || tokensMatch(it, anchorToken) }

        return Lexical(
            coverage = coverage,
            anchor = anchor,
            subset = subset,
            acronym = acronym,
            equal = equal,
            matched = matched,
            anchorMatched = anchorMatched,
        )
    }

    private fun rejected(
        candidate: MatchCandidate,
        release: ReleaseTitle,
        reasons: List<String>,
    ): TitleMatch = TitleMatch(
        candidate = candidate,
        release = release,
        score = 0.0,
        confidence = MatchConfidence.NONE,
        signals = MatchSignals(),
        reasons = reasons,
        rejected = true,
    )

    // ---------------------------------------------------------------- token similarity

    /**
     * Exact, singular/plural, a shared prefix, or a small edit distance. Two words of four letters
     * apart are never "the same word" — that is how "Naruto" would start matching "Naruto Shippuden"
     * episode titles by accident.
     */
    internal fun tokensMatch(a: String, b: String): Boolean {
        if (a == b) return true
        val left = singular(a)
        val right = singular(b)
        if (left == right) return true

        val shorter = min(left.length, right.length)
        val longer = max(left.length, right.length)
        if (shorter < 4) return false
        if (shorter.toDouble() / longer < 0.7) return false

        if (shorter >= 5 && (left.startsWith(right) || right.startsWith(left))) return true

        val allowed = if (longer <= 5) 1 else 2
        return levenshtein(left, right, allowed) <= allowed
    }

    /** "journeys" → "journey", "series" stays "series". */
    internal fun singular(token: String): String =
        if (token.length > 4 && token.endsWith("s") && !token.endsWith("ss")) {
            token.dropLast(1)
        } else {
            token
        }

    private fun acronymMatch(from: List<String>, against: List<String>): Boolean {
        if (from.size < 3) return false
        val acronym = from.joinToString("") { it.take(1) }
        if (acronym.length !in 2..6) return false
        return against.any { it == acronym }
    }

    /** Banded Levenshtein: stops as soon as the distance is known to exceed [limit]. */
    internal fun levenshtein(a: String, b: String, limit: Int): Int {
        if (a == b) return 0
        if (kotlin.math.abs(a.length - b.length) > limit) return limit + 1

        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)

        for (i in 1..a.length) {
            current[0] = i
            var rowMin = current[0]
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = min(
                    min(current[j - 1] + 1, previous[j] + 1),
                    previous[j - 1] + cost,
                )
                if (current[j] < rowMin) rowMin = current[j]
            }
            if (rowMin > limit) return limit + 1
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }
}
