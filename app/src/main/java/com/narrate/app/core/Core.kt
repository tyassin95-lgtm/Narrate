package com.narrate.app.core

import kotlinx.serialization.json.Json
import java.util.UUID

/** Application-wide JSON, configured to survive the noisy output of language models. */
val AppJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    encodeDefaults = true
    explicitNulls = false
    prettyPrint = false
}

val PrettyJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    encodeDefaults = true
    explicitNulls = false
    prettyPrint = true
}

const val LIST_SEP = "|~|"

fun newId(): String = UUID.randomUUID().toString()

fun String.truncate(max: Int): String =
    if (length <= max) this else take(max).substringBeforeLast(' ', take(max)) + "..."

/** Normalised form used for fuzzy name matching so "Elena Vasquez" matches "elena  vasquez". */
fun String.normalizeName(): String =
    lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

/** Cheap similarity in 0..1 used by the continuity guard to detect duplicate entities. */
fun nameSimilarity(a: String, b: String): Double {
    val x = a.normalizeName()
    val y = b.normalizeName()
    if (x.isEmpty() || y.isEmpty()) return 0.0
    if (x == y) return 1.0
    val xs = x.split(' ').toSet()
    val ys = y.split(' ').toSet()
    val overlap = xs.intersect(ys).size.toDouble()
    if (overlap > 0) return overlap / maxOf(xs.size, ys.size).toDouble()
    return if (x.length >= 4 && y.contains(x)) 0.7 else if (y.length >= 4 && x.contains(y)) 0.7 else 0.0
}

fun listToText(value: List<String>): String = value.filter { it.isNotBlank() }.joinToString(LIST_SEP)

fun textToList(value: String?): List<String> =
    value?.split(LIST_SEP)?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()

fun List<String>.joinNonBlank(separator: String = ", "): String =
    filter { it.isNotBlank() }.joinToString(separator)

class NarrateException(message: String, cause: Throwable? = null) : Exception(message, cause)
