package com.collection.ui

import java.io.BufferedReader

/** Bounded-memory full original reader; adjacent pages never split a Unicode surrogate pair. */
internal fun readOriginalTextPage(reader: BufferedReader, page: Int, pageSize: Int = 64_000): Pair<String, Boolean> {
    require(page >= 0 && pageSize >= 2)
    var skip = (page.toLong() * pageSize - if (page > 0) 1 else 0).coerceAtLeast(0)
    while (skip > 0) {
        val count = reader.skip(skip)
        if (count > 0) skip -= count else if (reader.read() < 0) return "" to false else skip--
    }
    val previous = if (page > 0) reader.read() else -1
    reader.mark(2)
    val first = reader.read()
    val shifted = previous >= 0 && first >= 0 && Character.isHighSurrogate(previous.toChar()) && Character.isLowSurrogate(first.toChar())
    if (!shifted) reader.reset()
    val chars = CharArray(pageSize - if (shifted) 1 else 0)
    var count = 0
    while (count < chars.size) {
        val read = reader.read(chars, count, chars.size - count)
        if (read < 0) break
        count += read
    }
    val result = StringBuilder().append(chars, 0, count)
    if (result.isNotEmpty() && Character.isHighSurrogate(result.last())) {
        reader.mark(2)
        val next = reader.read()
        if (next >= 0 && Character.isLowSurrogate(next.toChar())) result.append(next.toChar()) else reader.reset()
    }
    return result.toString() to (reader.read() >= 0)
}
