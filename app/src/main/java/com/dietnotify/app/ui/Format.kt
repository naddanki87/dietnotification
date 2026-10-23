package com.dietnotify.app.ui

import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val TIME_FORMAT = DateTimeFormatter.ofPattern("hh:mm a", Locale.ENGLISH)

fun formatTime(hour: Int, minute: Int): String = LocalTime.of(hour, minute).format(TIME_FORMAT)

fun dayName(isoDay: Int): String = DayOfWeek.of(isoDay).getDisplayName(TextStyle.FULL, Locale.getDefault())
