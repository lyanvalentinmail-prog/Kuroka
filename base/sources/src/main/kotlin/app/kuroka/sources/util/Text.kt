package app.kuroka.sources.util

import app.kuroka.api.AnimeRef
import app.kuroka.api.Title
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/*
 * The string work every provider needs: what a title is once the punctuation is gone, how alike
 * two of them are, and how the HTML entities of a scraped page come back as text.
 *
 * Ported from Kitsune's provider helpers (MIT, © Dovakiin0,
 * https://github.com/Dovakiin0/Kitsune, src/lib/providers/core/new-provider-utils.js).
 * This file is part of Kuroka (GPL-3.0, see ../../../LICENSE).
 */

/** `&#233;`, `&quot;`, `&amp;` and friends, back to the characters they stand for. */
fun decodeEntities(s: String?): String {
    // Not `if (s.isNullOrEmpty()) return ""` with `var out = s`: the local has to be a String of
    // its own, or every call on it below is a call on a nullable one.
    val text = s ?: return ""
    var out = text
    out = DECIMAL.replace(out) { m ->
        val code = m.groupValues[1].toIntOrNull() ?: return@replace m.value
        runCatching { String(Character.toChars(code)) }.getOrDefault(m.value)
    }
    out = HEX.replace(out) { m ->
        val code = m.groupValues[1].toIntOrNull(16) ?: return@replace m.value
        runCatching { String(Character.toChars(code)) }.getOrDefault(m.value)
    }
    NAMED.forEach { (name, char) -> out = out.replace("&$name;", char) }
    return out.trim()
}

private val DECIMAL = Regex("&#(\\d+);")
private val HEX = Regex("&#x([0-9a-fA-F]+);")
private val NAMED = listOf(
    "quot" to "\"", "apos" to "'", "lsquo" to "\u2018", "rsquo" to "\u2019",
    "ldquo" to "\u201C", "rdquo" to "\u201D", "amp" to "&", "lt" to "<", "gt" to ">",
    "nbsp" to " ", "ndash" to "\u2013", "mdash" to "\u2014", "hellip" to "\u2026",
    "eacute" to "\u00E9", "egrave" to "\u00E8", "agrave" to "\u00E0", "ccedil" to "\u00E7",
    "ntilde" to "\u00F1", "uuml" to "\u00FC", "ouml" to "\u00F6", "auml" to "\u00E4",
    "times" to "\u00D7", "middot" to "\u00B7",
)

/** The tags of an HTML fragment, gone. */
fun stripTags(html: String?): String =
    if (html.isNullOrEmpty()) "" else decodeEntities(TAGS.replace(html, " ")).replace(WHITESPACE, " ").trim()

private val TAGS = Regex("<[^>]*>")
private val WHITESPACE = Regex("\\s+")

/** A title reduced to the letters and digits a comparison can use ("One Piece" → "onepiece"). */
fun normalize(s: String?): String =
    if (s.isNullOrEmpty()) "" else s.lowercase().replace(NOT_ALNUM, "")

private val NOT_ALNUM = Regex("[^a-z0-9\\u00C0-\\u024F\\u3040-\\u30FF\\u4E00-\\u9FFF]")

/**
 * The words of a title that say which release it is ("Season 2", "Part 2", "Movie", "OVA"): a
 * candidate with one the target does not have is another release, and scores far lower.
 */
val MODIFIERS = listOf(
    "season", "part", "movie", "ova", "special", "specials", "tales", "journal", "spin-off",
    "spinoff", "theatre", "theater", "recap", "summary", "complete", "uncut", "uncensored",
)

/** How alike two strings are, 0f to 1f (Dice over their bigrams, as Kitsune scores candidates). */
fun diceCoefficient(a: String?, b: String?): Float {
    val x = normalize(a)
    val y = normalize(b)
    if (x.isEmpty() || y.isEmpty()) return 0f
    if (x == y) return 1f
    if (x.length < 2 || y.length < 2) return 0f
    val grams = HashMap<String, Int>()
    for (i in 0 until x.length - 1) {
        val g = x.substring(i, i + 2)
        grams[g] = (grams[g] ?: 0) + 1
    }
    var hits = 0
    for (i in 0 until y.length - 1) {
        val g = y.substring(i, i + 2)
        val left = grams[g] ?: continue
        if (left > 0) {
            grams[g] = left - 1
            hits++
        }
    }
    return (2f * hits) / (x.length - 1 + y.length - 1)
}

/**
 * How well a candidate's titles match the ones asked for, in points: 1000 for an exact romaji or
 * English match, less for a partial one, and a heavy penalty when the candidate is another
 * release of the same story (a movie of a season, a recap). The best candidate of a search wins.
 */
fun titleScore(target: AnimeRef, candidate: String?, candidateJp: String? = null, slug: String? = null): Int {
    val titles = buildTitles(target.title, target.synonyms)
    if (titles.isEmpty()) return 0
    val cand = normalize(candidate)
    val candJp = normalize(candidateJp)
    val candSlug = normalize(slug ?: candidate)
    if (cand.isEmpty() && candJp.isEmpty()) return 0

    var score = 0
    val haystack = StringBuilder()
    titles.forEach { haystack.append(it).append(' ') }
    val hay = haystack.toString().lowercase()

    for (title in titles) {
        val norm = normalize(title)
        if (norm.length < 2) continue
        when {
            cand == norm -> score = max(score, 1000)
            candJp == norm -> score = max(score, 900)
            candSlug == norm -> score = max(score, 850)
            cand.startsWith(norm) || norm.startsWith(cand) -> score = max(score, 400 + 80 * diceCoefficient(cand, norm).toInt())
            cand.contains(norm) || norm.contains(cand) -> score = max(score, 200 + 60 * diceCoefficient(cand, norm).toInt())
            diceCoefficient(cand, norm) >= 0.8f -> score = max(score, 150 + (diceCoefficient(cand, norm) * 100).toInt())
            else -> score = max(score, (diceCoefficient(cand, norm) * 60).toInt())
        }
    }

    // Another release of the same story: a modifier one side has and the other has not.
    for (mod in MODIFIERS) {
        val candHas = cand.contains(mod) || candSlug.contains(mod)
        val targetHas = hay.contains(mod)
        if (candHas && !targetHas) score -= 350
        if (targetHas && !candHas) score -= 90
    }
    // A year in the candidate that is not the anime's own: another release.
    val year = YEAR.find(candidate ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull()
    // `target.year` is another module's property: it has to be held in a local before it can be
    // used as the Int it was just found to be.
    val targetYear = target.year
    if (year != null && targetYear != null && abs(year - targetYear) > 1) score -= 250
    // The longer the difference in length, the less alike two titles that matched partly are.
    val best = titles.maxOfOrNull { normalize(it).length } ?: 0
    score -= min(abs(cand.length - best), 30) * 2
    return score
}

private val YEAR = Regex("(19|20)\\d{2}")

/** Every title of an anime, the ones a provider may list it under, without duplicates. */
fun buildTitles(title: Title, synonyms: List<String> = emptyList(), extra: List<String?> = emptyList()): List<String> =
    (listOf(title.english, title.romaji, title.native, title.userPreferred) + extra + synonyms)
        .filterNotNull().map { it.trim() }.filter { it.isNotBlank() }.distinct()

/** The searches to try, from the shortest and most likely title to the longest. */
fun searchQueries(titles: List<String>, limit: Int = 12): List<String> =
    titles.flatMap { listOf(it, it.replace(SUBTITLE_MARK, " "), it.replace(NOT_WORD, " ").trim()) }
        .map { it.trim() }
        .filter { it.length >= 2 }
        .distinctBy { normalize(it) }
        .sortedBy { it.length }
        .take(limit)

private val SUBTITLE_MARK = Regex("\\s*[:\\-–—]\\s*")
private val NOT_WORD = Regex("[^\\p{L}\\p{N}\\s]")

/** "13.5" as a number, or null (an episode list with a "13.5" or an "OVA 2" in it). */
fun episodeNumber(text: String?): Double? {
    if (text.isNullOrBlank()) return null
    val cleaned = text.replace(WHITESPACE, "")
    val direct = cleaned.toDoubleOrNull()
    if (direct != null && direct > 0) return direct
    val m = Regex("(\\d+(?:\\.\\d+)?)").find(cleaned) ?: return null
    val n = m.groupValues[1].toDoubleOrNull() ?: return null
    return if (n > 0) n else null
}

/** A duration in seconds, as "24 min", "24:10" or "1450". */
fun durationSeconds(text: String?): Int? {
    if (text.isNullOrBlank()) return null
    Regex("(\\d+)\\s*h(?:our)?s?").find(text)?.let {
        val h = it.groupValues[1].toIntOrNull() ?: 0
        val m = Regex("(\\d+)\\s*m(?:in)?s?").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return h * 3600 + m * 60
    }
    Regex("(\\d+):(\\d{2})").find(text)?.let {
        val a = it.groupValues[1].toIntOrNull() ?: return null
        val b = it.groupValues[2].toIntOrNull() ?: return null
        return a * 60 + b
    }
    val n = text.toIntOrNull() ?: Regex("(\\d+)\\s*(?:min|m)\\b").find(text)?.groupValues?.get(1)?.toIntOrNull()
    return n?.takeIf { it in 1..20000 }
}

/** "1.2M" and "12,345" as the numbers they are (a scraped page's counts). */
fun countValue(text: String?): Int? {
    if (text.isNullOrBlank()) return null
    val t = text.trim().replace(",", "")
    val m = Regex("(\\d+(?:\\.\\d+)?)\\s*([KkMm])?").find(t) ?: return null
    val base = m.groupValues[1].toDoubleOrNull() ?: return null
    return when (m.groupValues[2].lowercase()) {
        "k" -> (base * 1000).toInt()
        "m" -> (base * 1000000).toInt()
        else -> base.toInt()
    }
}
