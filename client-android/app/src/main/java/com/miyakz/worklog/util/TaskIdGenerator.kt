package com.miyakz.worklog.util

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.random.Random

/**
 * Generates task_ids of the form `yyyyMMdd-HHmmss-xxxx`: shorter and more
 * human-readable than a UUID, sortable, and needs no extra library.
 */
object TaskIdGenerator {
    private val TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    private const val SUFFIX_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789"
    private const val SUFFIX_LENGTH = 4

    fun generate(now: LocalDateTime = LocalDateTime.now()): String {
        val timestamp = now.format(TIMESTAMP_FORMAT)
        val suffix = (1..SUFFIX_LENGTH)
            .map { SUFFIX_CHARS[Random.nextInt(SUFFIX_CHARS.length)] }
            .joinToString("")
        return "$timestamp-$suffix"
    }
}
