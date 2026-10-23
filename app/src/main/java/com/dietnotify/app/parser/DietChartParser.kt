package com.dietnotify.app.parser

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/** One visual line of text recognised on a page, with its bounding box in pixels. */
data class OcrLine(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val centerY: Int get() = (top + bottom) / 2
    val height: Int get() = (bottom - top).coerceAtLeast(1)
}

data class OcrPage(val width: Int, val height: Int, val lines: List<OcrLine>)

/** A single meal in the weekly plan. [dayOfWeek] uses ISO numbering: 1 = Monday ... 7 = Sunday. */
data class ParsedMeal(
    val dayOfWeek: Int,
    val name: String,
    val hour: Int,
    val minute: Int,
    val recipe: String,
)

sealed class ParseResult {
    data class Success(val meals: List<ParsedMeal>) : ParseResult()
    data class Invalid(val reason: String) : ParseResult()
}

/**
 * Turns OCR output of a diet chart PDF into a weekly list of meals.
 *
 * The parser expects the common diet-chart layout: every meal row has a meal name
 * ("Breakfast") with its time ("09:00 AM") underneath or next to it, and the
 * food / recipe text either to the right of it (two-column table) or below it.
 * Each day is announced by a heading containing the weekday ("Sunday"), a date
 * ("18-10-2026") or a day number ("DAY 1"). A chart without any day headings is
 * treated as a daily plan and repeated on every day of the week.
 */
object DietChartParser {

    const val INVALID_MESSAGE = "Upload valid diet chart"

    private val TIME_12H = Regex(
        """(?<![\d:])(\d{1,2})\s*[:.]\s*(\d{2})\s*([AaPp])\s*\.?\s*[Mm]\b\.?"""
    )
    private val TIME_24H = Regex("""(?<![\d:])([01]?\d|2[0-3])\s*:\s*([0-5]\d)(?![\d:])""")
    private val DATE = Regex("""(?<!\d)(\d{1,2})\s*[-/.]\s*(\d{1,2})\s*[-/.]\s*(\d{4})(?!\d)""")
    private val DAY_NUMBER = Regex("""\bDAY\s*[-:#]?\s*(\d{1,2})\b""", RegexOption.IGNORE_CASE)

    private val WEEKDAYS = listOf(
        Regex("""\bmon(day)?\b""", RegexOption.IGNORE_CASE) to DayOfWeek.MONDAY,
        Regex("""\btue(s|sday)?\b""", RegexOption.IGNORE_CASE) to DayOfWeek.TUESDAY,
        Regex("""\bwed(nesday)?\b""", RegexOption.IGNORE_CASE) to DayOfWeek.WEDNESDAY,
        Regex("""\bthu(r|rs|rsday)?\b""", RegexOption.IGNORE_CASE) to DayOfWeek.THURSDAY,
        Regex("""\bfri(day)?\b""", RegexOption.IGNORE_CASE) to DayOfWeek.FRIDAY,
        Regex("""\bsat(urday)?\b""", RegexOption.IGNORE_CASE) to DayOfWeek.SATURDAY,
        Regex("""\bsun(day)?\b""", RegexOption.IGNORE_CASE) to DayOfWeek.SUNDAY,
    )

    private val DIET_KEYWORDS = listOf(
        "breakfast", "lunch", "dinner", "snack", "meal", "diet", "morning", "evening",
        "brunch", "supper", "tiffin", "nutrition", "calorie", "dietitian", "dietician",
    )

    private const val MIN_MEALS = 3

    private sealed class DayKey {
        data class Weekday(val day: DayOfWeek) : DayKey()
        data class Relative(val number: Int) : DayKey()
    }

    private data class RawMeal(val day: DayKey?, val name: String, val hour: Int, val minute: Int, val recipe: String)

    private data class TimeHit(val line: OcrLine, val hour: Int, val minute: Int, val leftover: String)

    private data class Section(val time: TimeHit, val nameLine: OcrLine?, val name: String) {
        val top: Int get() = nameLine?.top?.coerceAtMost(time.line.top) ?: time.line.top
        val bottom: Int get() = time.line.bottom.coerceAtLeast(nameLine?.bottom ?: 0)
        val centerY: Int get() = (top + bottom) / 2
    }

    fun parse(pages: List<OcrPage>, today: LocalDate = LocalDate.now()): ParseResult {
        val fullText = pages.flatMap { it.lines }.joinToString(" ") { it.text }.lowercase()
        if (DIET_KEYWORDS.none { fullText.contains(it) }) {
            return ParseResult.Invalid(INVALID_MESSAGE)
        }

        val raw = mutableListOf<RawMeal>()
        var currentDay: DayKey? = null
        for (page in pages) {
            val result = parsePage(page, currentDay)
            currentDay = result.first
            raw += result.second
        }
        if (raw.size < MIN_MEALS) return ParseResult.Invalid(INVALID_MESSAGE)

        val meals = resolveDays(raw, today)
            .distinctBy { Triple(it.dayOfWeek, it.hour, it.minute) }
            .sortedWith(compareBy({ it.dayOfWeek }, { it.hour }, { it.minute }))
        if (meals.size < MIN_MEALS) return ParseResult.Invalid(INVALID_MESSAGE)
        return ParseResult.Success(meals)
    }

    private fun resolveDays(raw: List<RawMeal>, today: LocalDate): List<ParsedMeal> {
        val firstKnown = raw.firstOrNull { it.day != null }?.day
        if (firstKnown == null) {
            // No day headings at all: the same plan applies to every day of the week.
            return DayOfWeek.entries.flatMap { day ->
                raw.map { ParsedMeal(day.value, it.name, it.hour, it.minute, it.recipe) }
            }
        }
        return raw.map { meal ->
            val day = when (val key = meal.day ?: firstKnown) {
                is DayKey.Weekday -> key.day
                is DayKey.Relative -> today.plusDays((key.number - 1).toLong()).dayOfWeek
            }
            ParsedMeal(day.value, meal.name, meal.hour, meal.minute, meal.recipe)
        }
    }

    /** Returns the day in effect at the end of the page and the meals found on it. */
    private fun parsePage(page: OcrPage, dayBefore: DayKey?): Pair<DayKey?, List<RawMeal>> {
        val lines = page.lines.filter { it.text.isNotBlank() }.sortedWith(compareBy({ it.top }, { it.left }))
        val sections = findSections(page, lines)
        if (sections.isEmpty()) {
            // Pages without meals (cover, basic details...) may still announce the next day.
            val day = findDay(lines) ?: dayBefore
            return day to emptyList()
        }

        val firstTop = sections.first().top
        val day = findDay(lines.filter { it.bottom <= firstTop }) ?: dayBefore

        val spacing = sections.zipWithNext { a, b -> b.centerY - a.centerY }.sorted()
            .let { if (it.isEmpty()) (sections.first().bottom - firstTop) * 3 else it[it.size / 2] }

        // The page heading (day, date...) sits well above the first meal; text just
        // above the first meal name still belongs to that meal's recipe.
        val headerBottom = firstTop - spacing / 2
        val used = sections.flatMap { listOfNotNull(it.nameLine, it.time.line) }.toSet()
        val leftColumnRight = sections.maxOf { s -> maxOf(s.time.line.right, s.nameLine?.right ?: 0) }
        val bodyLines = lines.filter { it !in used && it.centerY >= headerBottom }

        // Two-column table: the food text sits to the right of the meal name/time,
        // vertically centred on it. Otherwise the food text follows below the time.
        val twoColumn = sections.count { s ->
            bodyLines.any { it.left >= leftColumnRight && it.bottom >= s.top && it.top <= s.bottom }
        } * 2 >= sections.size

        val meals = sections.mapIndexed { i, s ->
            val prev = sections.getOrNull(i - 1)
            val next = sections.getOrNull(i + 1)
            val (from, to) = if (twoColumn) {
                val from = if (prev == null) headerBottom else (prev.centerY + s.centerY) / 2
                val to = if (next == null) s.centerY + spacing * 3 / 4 else (s.centerY + next.centerY) / 2
                from to to
            } else {
                val to = next?.top ?: (s.bottom + spacing * 5 / 4)
                s.top to to
            }
            val content = bodyLines.filter { it.centerY in from until to }
                .filter { !twoColumn || it.left >= leftColumnRight - page.width / 100 }
            RawMeal(day, s.name, s.time.hour, s.time.minute, joinLines(content))
        }
        return day to meals
    }

    private fun findSections(page: OcrPage, lines: List<OcrLine>): List<Section> {
        val hits = lines.mapNotNull { timeHit(it) }
        if (hits.isEmpty()) return emptyList()

        // Meal times line up in one column; drop times mentioned inside recipe text.
        val medianLeft = hits.map { it.line.left }.sorted()[hits.size / 2]
        val column = hits.filter { abs(it.line.left - medianLeft) <= page.width * 8 / 100 }

        return column.map { hit ->
            val inline = hit.leftover.takeIf { it.count(Char::isLetter) >= 3 }
            val nameLine = if (inline != null) null else lines.filter { candidate ->
                candidate !== hit.line &&
                    timeHit(candidate) == null &&
                    candidate.bottom <= hit.line.top + hit.line.height / 2 &&
                    hit.line.top - candidate.bottom <= hit.line.height * 5 / 2 &&
                    abs(candidate.left - hit.line.left) <= page.width * 6 / 100 &&
                    candidate.text.count(Char::isLetter) >= 3
            }.maxByOrNull { it.bottom }
            val name = cleanName(inline ?: nameLine?.text) ?: defaultName(hit.hour)
            Section(hit, nameLine, name)
        }.sortedBy { it.top }
    }

    private fun timeHit(line: OcrLine): TimeHit? {
        val text = line.text
        TIME_12H.find(text)?.let { m ->
            val h = m.groupValues[1].toInt()
            val min = m.groupValues[2].toInt()
            if (h !in 1..12 || min > 59) return null
            val pm = m.groupValues[3].equals("p", ignoreCase = true)
            val hour24 = (h % 12) + if (pm) 12 else 0
            return TimeHit(line, hour24, min, text.removeRange(m.range))
        }
        TIME_24H.find(text)?.let { m ->
            val leftover = text.removeRange(m.range)
            // A bare 24h time is only trusted when the line is short (a time label, not a sentence).
            if (leftover.count(Char::isLetter) > 20) return null
            return TimeHit(line, m.groupValues[1].toInt(), m.groupValues[2].toInt(), leftover)
        }
        return null
    }

    private fun findDay(lines: List<OcrLine>): DayKey? {
        val text = lines.joinToString(" ") { it.text }
        WEEKDAYS.firstOrNull { (regex, _) -> regex.containsMatchIn(text) }?.let { return DayKey.Weekday(it.second) }
        DATE.find(text)?.let { m ->
            val parsed = runCatching {
                LocalDate.parse(
                    "%02d-%02d-%s".format(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3]),
                    DateTimeFormatter.ofPattern("dd-MM-yyyy"),
                )
            }.getOrNull()
            if (parsed != null) return DayKey.Weekday(parsed.dayOfWeek)
        }
        DAY_NUMBER.find(text)?.let { return DayKey.Relative(it.groupValues[1].toInt().coerceAtLeast(1)) }
        return null
    }

    private fun cleanName(text: String?): String? {
        if (text == null) return null
        val cleaned = text
            .replace(Regex("""[|:\-–—()\[\]@©®•*]+"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .trimStart { !it.isLetter() }
        return cleaned.takeIf { it.count(Char::isLetter) >= 3 }
    }

    private fun defaultName(hour: Int): String = when (hour) {
        in 4..7 -> "Early Morning"
        in 8..10 -> "Breakfast"
        in 11..12 -> "Mid Meal"
        in 13..15 -> "Lunch"
        in 16..18 -> "Evening Snack"
        in 19..20 -> "Dinner"
        else -> "Meal"
    }

    private fun joinLines(lines: List<OcrLine>): String {
        if (lines.isEmpty()) return ""
        val sorted = lines.sortedWith(compareBy({ it.top }, { it.left }))
        val sb = StringBuilder(sorted.first().text.trim())
        for ((prev, line) in sorted.zipWithNext()) {
            val gap = line.top - prev.bottom
            sb.append(if (gap > prev.height * 6 / 10) "\n" else " ")
            sb.append(line.text.trim())
        }
        return sb.toString().replace(Regex("""[ \t]+"""), " ").trim()
    }
}
