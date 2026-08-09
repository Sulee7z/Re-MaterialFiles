/*
 * Copyright (c) 2026 Sulee7z <94352968+sulee7z@users.noreply.github.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.searchindex

import java.util.Calendar
import java.util.Locale

/**
 * A parsed Everything-style search query.
 *
 * Semantics:
 *  - Space separates AND terms ("图片 2026" matches both).
 *  - `|` separates OR alternatives ("jpg | png" matches either; each side may itself
 *    contain multiple AND terms).
 *  - `!` excludes a term ("vue !min" matches "vue" but not "min").
 *  - `"..."` matches the phrase exactly (may contain spaces).
 *  - `file:`/`folder:`/`doc:`/`pic:`/`video:`/`zip:` filter by category.
 *  - `size:>10mb`, `size:<5kb`, `size:1mb..50mb` filter by size (KB/MB/GB, 1024-based).
 *  - `dm:thisyear`, `dm:today`, `dm:2026-08-01..2026-08-09` filter by modification date.
 *  - A leading `/path/` scopes the search to that directory and its descendants.
 */
data class SearchQuery(
    /** OR alternatives; each item is a list of AND terms (already stripped of operators). */
    val subgroups: List<List<String>> = emptyList(),
    /** Terms that must not appear in the name. */
    val excludeTerms: List<String> = emptyList(),
    /** Phrases (may contain spaces) that must appear verbatim. */
    val exactPhrases: List<String> = emptyList(),
    val fileOnly: Boolean = false,
    val folderOnly: Boolean = false,
    val docOnly: Boolean = false,
    val picOnly: Boolean = false,
    val videoOnly: Boolean = false,
    val zipOnly: Boolean = false,
    val minSize: Long? = null,
    val maxSize: Long? = null,
    val minMtimeMillis: Long? = null,
    val maxMtimeMillis: Long? = null,
    /** Absolute directory prefix restricting the search scope. */
    val pathPrefix: String? = null
) {
    /** Plain space-joined keywords, used when falling back to the recursive tree walk. */
    val simpleKeywords: String
        get() = (subgroups.flatten() + exactPhrases).joinToString(" ")

    val isEmpty: Boolean
        get() = subgroups.isEmpty() && excludeTerms.isEmpty() && exactPhrases.isEmpty() &&
            !fileOnly && !folderOnly && !docOnly && !picOnly && !videoOnly && !zipOnly &&
            minSize == null && maxSize == null && minMtimeMillis == null &&
            maxMtimeMillis == null
}

object SearchQueryParser {

    private val SIZE_PATTERN = Regex("^([<>])?(\\d+(?:\\.\\d+)?)([kmgt]?b)$", RegexOption.IGNORE_CASE)
    private val DATE_RANGE_PATTERN = Regex("^(\\d{4}-\\d{2}-\\d{2})\\.\\.(\\d{4}-\\d{2}-\\d{2})$")

    fun parse(rawQuery: String): SearchQuery {
        var query = rawQuery.trim()
        if (query.isEmpty()) {
            return SearchQuery()
        }
        var pathPrefix: String? = null
        if (query.startsWith("/") && query != "/") {
            // Everything-style path scope: "/path/to/folder keyword" (first space separates).
            val spaceIndex = query.indexOf(' ')
            val pathText = if (spaceIndex == -1) query else query.substring(0, spaceIndex)
            if (isPathLike(pathText)) {
                pathPrefix = normalizePathPrefix(pathText.trimEnd('/'))
                query = if (spaceIndex == -1) "" else query.substring(spaceIndex + 1).trim()
            }
        }

        val tokens = tokenize(query)
        var currentSubgroup = mutableListOf<String>()
        val subgroups = mutableListOf(currentSubgroup)
        val excludeTerms = mutableListOf<String>()
        val exactPhrases = mutableListOf<String>()
        var fileOnly = false
        var folderOnly = false
        var docOnly = false
        var picOnly = false
        var videoOnly = false
        var zipOnly = false
        var minSize: Long? = null
        var maxSize: Long? = null
        var minMtime: Long? = null
        var maxMtime: Long? = null

        for (token in tokens) {
            when {
                token == "|" -> {
                    currentSubgroup = mutableListOf()
                    subgroups += currentSubgroup
                }
                token.startsWith("!") -> {
                    val term = token.substring(1).trim('"')
                    if (term.isNotEmpty()) {
                        excludeTerms += term
                    }
                }
                token.startsWith("\"") && token.endsWith("\"") && token.length >= 2 -> {
                    exactPhrases += token.substring(1, token.length - 1)
                }
                token.startsWith("file:") -> fileOnly = true
                token.startsWith("folder:") -> folderOnly = true
                token.startsWith("doc:") -> docOnly = true
                token.startsWith("pic:") -> picOnly = true
                token.startsWith("video:") -> videoOnly = true
                token.startsWith("zip:") -> zipOnly = true
                token.startsWith("size:") -> {
                    parseSize(token.substring(5))?.let { (lo, hi) ->
                        if (lo != null) minSize = minSize?.let { maxOf(it, lo) } ?: lo
                        if (hi != null) maxSize = maxSize?.let { minOf(it, hi) } ?: hi
                    }
                }
                token.startsWith("dm:") -> {
                    parseDate(token.substring(3))?.let { (lo, hi) ->
                        if (lo != null) minMtime = minMtime?.let { maxOf(it, lo) } ?: lo
                        if (hi != null) maxMtime = maxMtime?.let { minOf(it, hi) } ?: hi
                    }
                }
                else -> {
                    if (token.isNotEmpty()) {
                        currentSubgroup += token
                    }
                }
            }
        }

        val nonEmptySubgroups = subgroups.filter { it.isNotEmpty() }
        return SearchQuery(
            subgroups = nonEmptySubgroups,
            excludeTerms = excludeTerms,
            exactPhrases = exactPhrases,
            fileOnly = fileOnly,
            folderOnly = folderOnly,
            docOnly = docOnly,
            picOnly = picOnly,
            videoOnly = videoOnly,
            zipOnly = zipOnly,
            minSize = minSize,
            maxSize = maxSize,
            minMtimeMillis = minMtime,
            maxMtimeMillis = maxMtime,
            pathPrefix = pathPrefix
        )
    }

    /**
     * Splits a raw query on spaces, honoring double-quoted phrases, `|` operators and
     * `!`/`file:`-style tokens that never contain spaces.
     */
    private fun tokenize(query: String): List<String> {
        val tokens = mutableListOf<String>()
        var index = 0
        while (index < query.length) {
            val char = query[index]
            when {
                char == '"' -> {
                    val end = query.indexOf('"', index + 1)
                    if (end == -1) {
                        tokens += query.substring(index)
                        break
                    }
                    tokens += query.substring(index, end + 1)
                    index = end + 1
                }
                char == '|' -> {
                    tokens += "|"
                    index++
                }
                char.isWhitespace() -> index++
                else -> {
                    val end = query.indexOfAny(charArrayOf(' ', '"', '|'), index)
                    if (end == -1) {
                        tokens += query.substring(index)
                        break
                    }
                    tokens += query.substring(index, end)
                    index = end
                }
            }
        }
        return tokens
    }

    private fun isPathLike(text: String): Boolean =
        text.startsWith("/") && text.length > 1

    /**
     * Maps the user-facing /sdcard alias to the real /storage/emulated/0 path that the index
     * uses, so scoped searches hit the index instead of falling back to a tree walk.
     */
    private fun normalizePathPrefix(prefix: String): String =
        if (prefix == "/sdcard" || prefix.startsWith("/sdcard/")) {
            "/storage/emulated/0" + prefix.removePrefix("/sdcard")
        } else {
            prefix
        }

    /** Parses ">10mb", "<5kb" or "1mb..50mb" into (min, max) bytes. */
    private fun parseSize(expr: String): Pair<Long?, Long?>? {
        val range = expr.split("..")
        if (range.size == 2) {
            val lo = parseSingleSize(range[0]) ?: return null
            val hi = parseSingleSize(range[1]) ?: return null
            return lo to hi
        }
        val match = SIZE_PATTERN.matchEntire(expr.trim()) ?: return null
        val value = match.groupValues[2].toDouble()
        val multiplier = when (match.groupValues[3].lowercase(Locale.ROOT)) {
            "kb" -> 1024L
            "mb" -> 1024L * 1024
            "gb" -> 1024L * 1024 * 1024
            else -> 1L
        }
        val bytes = (value * multiplier).toLong()
        return when (match.groupValues[1]) {
            ">" -> bytes to null
            "<" -> null to bytes
            else -> bytes to bytes
        }
    }

    private fun parseSingleSize(text: String): Long? {
        val match = SIZE_PATTERN.matchEntire(text.trim()) ?: return null
        if (match.groupValues[1].isNotEmpty()) {
            return null
        }
        val value = match.groupValues[2].toDouble()
        val multiplier = when (match.groupValues[3].lowercase(Locale.ROOT)) {
            "kb" -> 1024L
            "mb" -> 1024L * 1024
            "gb" -> 1024L * 1024 * 1024
            else -> 1L
        }
        return (value * multiplier).toLong()
    }

    /** Parses "thisyear", "today", "thismonth" or "2026-08-01..2026-08-09" into millis. */
    private fun parseDate(expr: String): Pair<Long?, Long?>? {
        val text = expr.trim().lowercase(Locale.ROOT)
        val calendar = Calendar.getInstance()
        when (text) {
            "today" -> {
                calendar.set(Calendar.HOUR_OF_DAY, 0)
                calendar.set(Calendar.MINUTE, 0)
                calendar.set(Calendar.SECOND, 0)
                calendar.set(Calendar.MILLISECOND, 0)
                return calendar.timeInMillis to null
            }
            "yesterday" -> {
                calendar.add(Calendar.DAY_OF_YEAR, -1)
                calendar.set(Calendar.HOUR_OF_DAY, 0)
                calendar.set(Calendar.MINUTE, 0)
                calendar.set(Calendar.SECOND, 0)
                calendar.set(Calendar.MILLISECOND, 0)
                val end = calendar.timeInMillis + 24 * 60 * 60 * 1000 - 1
                return calendar.timeInMillis to end
            }
            "thisweek" -> {
                calendar.set(Calendar.DAY_OF_WEEK, calendar.firstDayOfWeek)
                calendar.set(Calendar.HOUR_OF_DAY, 0)
                calendar.set(Calendar.MINUTE, 0)
                calendar.set(Calendar.SECOND, 0)
                calendar.set(Calendar.MILLISECOND, 0)
                return calendar.timeInMillis to null
            }
            "thismonth" -> {
                calendar.set(Calendar.DAY_OF_MONTH, 1)
                calendar.set(Calendar.HOUR_OF_DAY, 0)
                calendar.set(Calendar.MINUTE, 0)
                calendar.set(Calendar.SECOND, 0)
                calendar.set(Calendar.MILLISECOND, 0)
                return calendar.timeInMillis to null
            }
            "thisyear" -> {
                calendar.set(Calendar.MONTH, Calendar.JANUARY)
                calendar.set(Calendar.DAY_OF_MONTH, 1)
                calendar.set(Calendar.HOUR_OF_DAY, 0)
                calendar.set(Calendar.MINUTE, 0)
                calendar.set(Calendar.SECOND, 0)
                calendar.set(Calendar.MILLISECOND, 0)
                return calendar.timeInMillis to null
            }
        }
        val range = DATE_RANGE_PATTERN.matchEntire(expr.trim()) ?: return null
        val start = parseDateMillis(range.groupValues[1]) ?: return null
        val end = parseDateMillis(range.groupValues[2]) ?: return null
        return start to (end + 24 * 60 * 60 * 1000 - 1)
    }

    private fun parseDateMillis(text: String): Long? {
        return try {
            val parts = text.split("-")
            val calendar = Calendar.getInstance()
            calendar.clear()
            calendar.set(parts[0].toInt(), parts[1].toInt() - 1, parts[2].toInt())
            calendar.timeInMillis
        } catch (e: Exception) {
            null
        }
    }
}
