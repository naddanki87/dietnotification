package com.dietnotify.app.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

class DietChartParserTest {

    private val today = LocalDate.of(2026, 10, 23) // a Friday

    /** Builds a page in the "meal name + time on the left, food on the right" table layout. */
    private fun tablePage(header: List<String>, meals: List<Triple<String, String, List<String>>>): OcrPage {
        val lines = mutableListOf<OcrLine>()
        header.forEachIndexed { i, text -> lines += OcrLine(text, 100 + i * 500, 80, 400 + i * 500, 120) }
        meals.forEachIndexed { i, (name, time, food) ->
            val top = 260 + i * 250
            lines += OcrLine(name, 106, top, 370, top + 35)
            lines += OcrLine("© $time", 104, top + 55, 278, top + 85)
            food.forEachIndexed { j, text ->
                val foodTop = top - 30 + j * 40
                lines += OcrLine(text, 520, foodTop, 1400, foodTop + 32)
            }
        }
        return OcrPage(1600, 2263, lines)
    }

    @Test
    fun parsesTwoColumnChartWithWeekdayHeaders() {
        val pages = listOf(
            OcrPage(1600, 2263, listOf(OcrLine("DIETARY ROADMAP TO HEALTHY LIFESTYLE", 200, 700, 1400, 760))),
            tablePage(
                listOf("DAY 1", "Sunday", "18-10-2026"),
                listOf(
                    Triple("Early Morning", "06:00 AM", listOf("Jeera Water (1 glass), Raisins Soaked", "(Soak overnight)")),
                    Triple("Breakfast", "08:30 AM", listOf("Poha (1 bowl)")),
                    Triple("Lunch", "02:00 PM", listOf("Oats Roti (2 roti), Moong Dal (1 cup)")),
                    Triple("Dinner", "07:30 PM", listOf("Daliya with Dal (1 bowl)", "Have it before 8:00 PM")),
                ),
            ),
            tablePage(
                listOf("DAY 2", "Monday", "19-10-2026"),
                listOf(
                    Triple("Breakfast", "09:00 AM", listOf("Rawa Idli (2 idli)")),
                    Triple("Lunch", "01:00 PM", listOf("Jeera rice (1 katori)")),
                ),
            ),
        )

        val result = DietChartParser.parse(pages, today) as ParseResult.Success
        assertEquals(6, result.meals.size)

        val sunday = result.meals.filter { it.dayOfWeek == DayOfWeek.SUNDAY.value }
        assertEquals(listOf("Early Morning", "Breakfast", "Lunch", "Dinner"), sunday.map { it.name })
        assertEquals(8 to 30, sunday[1].hour to sunday[1].minute)
        assertEquals(19 to 30, sunday[3].hour to sunday[3].minute)
        assertEquals("Jeera Water (1 glass), Raisins Soaked (Soak overnight)", sunday[0].recipe)
        assertEquals("Poha (1 bowl)", sunday[1].recipe)
        // A time inside the recipe text must not create an extra meal.
        assertTrue(sunday[3].recipe.contains("8:00 PM"))

        val monday = result.meals.filter { it.dayOfWeek == DayOfWeek.MONDAY.value }
        assertEquals(listOf("Rawa Idli (2 idli)", "Jeera rice (1 katori)"), monday.map { it.recipe })
    }

    @Test
    fun usesDateWhenWeekdayIsMissing() {
        val page = tablePage(
            listOf("20-10-2026"),
            listOf(
                Triple("Breakfast", "09:00 AM", listOf("Poha")),
                Triple("Lunch", "01:00 PM", listOf("Rice")),
                Triple("Dinner", "08:00 PM", listOf("Soup")),
            ),
        )
        val result = DietChartParser.parse(listOf(page), today) as ParseResult.Success
        assertTrue(result.meals.all { it.dayOfWeek == DayOfWeek.TUESDAY.value })
    }

    @Test
    fun dayNumbersStartFromToday() {
        val page = tablePage(
            listOf("Day 2"),
            listOf(
                Triple("Breakfast", "09:00 AM", listOf("Poha")),
                Triple("Lunch", "01:00 PM", listOf("Rice")),
                Triple("Dinner", "08:00 PM", listOf("Soup")),
            ),
        )
        val result = DietChartParser.parse(listOf(page), today) as ParseResult.Success
        assertTrue(result.meals.all { it.dayOfWeek == DayOfWeek.SATURDAY.value })
    }

    @Test
    fun chartWithoutDaysRepeatsEveryDay() {
        val lines = listOf(
            OcrLine("My Diet Plan", 100, 50, 600, 90),
            OcrLine("Breakfast - 8:30 am", 100, 200, 600, 240),
            OcrLine("Oats with milk (1 bowl)", 100, 250, 900, 290),
            OcrLine("Lunch - 13:00", 100, 400, 600, 440),
            OcrLine("Brown rice, dal", 100, 450, 900, 490),
            OcrLine("Dinner - 7.30 PM", 100, 600, 600, 640),
            OcrLine("Vegetable soup", 100, 650, 900, 690),
        )
        val result = DietChartParser.parse(listOf(OcrPage(1000, 1400, lines)), today) as ParseResult.Success
        assertEquals(21, result.meals.size)
        val monday = result.meals.filter { it.dayOfWeek == 1 }
        assertEquals(listOf("Breakfast", "Lunch", "Dinner"), monday.map { it.name })
        assertEquals(listOf(8 to 30, 13 to 0, 19 to 30), monday.map { it.hour to it.minute })
        assertEquals(listOf("Oats with milk (1 bowl)", "Brown rice, dal", "Vegetable soup"), monday.map { it.recipe })
    }

    @Test
    fun rejectsPdfThatIsNotADietChart() {
        val invoice = OcrPage(
            1000, 1400,
            listOf(
                OcrLine("INVOICE #4411", 100, 50, 600, 90),
                OcrLine("Delivered 10:30 AM", 100, 200, 600, 240),
                OcrLine("Total amount 4,500", 100, 300, 600, 340),
            ),
        )
        assertEquals(ParseResult.Invalid(DietChartParser.INVALID_MESSAGE), DietChartParser.parse(listOf(invoice), today))
    }

    @Test
    fun rejectsDietTextWithoutMealTimes() {
        val page = OcrPage(1000, 1400, listOf(OcrLine("Healthy diet tips: eat breakfast daily", 100, 50, 900, 90)))
        assertEquals(ParseResult.Invalid(DietChartParser.INVALID_MESSAGE), DietChartParser.parse(listOf(page), today))
    }
}
