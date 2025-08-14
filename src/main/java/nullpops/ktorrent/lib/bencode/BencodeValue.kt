/*
 *
 * Copyright (c) 2025 NullPops
 *
 * This file is part of ktorrent-lib.
 *
 * Licensed under the GNU Affero General Public License v3.0 (AGPLv3)
 * or a Commercial License.
 *
 * You may use this file under AGPLv3 if you release your project under
 * a compatible open source license. For closed source or commercial use,
 * you must obtain a commercial license from NullPops.
 *
 * See the LICENSE file for details.
 */

package nullpops.ktorrent.lib.bencode

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets
import kotlin.also
import kotlin.code
import kotlin.collections.map
import kotlin.collections.plus
import kotlin.collections.plusAssign
import kotlin.collections.reduce
import kotlin.collections.set
import kotlin.collections.sorted
import kotlin.collections.toString
import kotlin.io.inputStream
import kotlin.io.use
import kotlin.text.toByteArray
import kotlin.text.toInt
import kotlin.text.toLong

sealed class BencodeValue
data class BencodeInt(val value: Long) : BencodeValue()
data class BencodeString(val value: ByteArray) : BencodeValue() {
    fun asUtf8(): String = value.toString(Charsets.UTF_8)
}
data class BencodeList(val value: List<BencodeValue>) : BencodeValue()
data class BencodeDict(val value: Map<String, BencodeValue>) : BencodeValue()

class BencodeParser(src: InputStream) {
    val input = ByteArrayInputStream(src.readAllBytes())
    fun parse(): BencodeValue = parseValue()

    private fun parseValue(): BencodeValue {
        return when (val c = input.read().toChar()) {
            'i' -> parseInt()
            'l' -> parseList()
            'd' -> parseDict()
            in '0'..'9' -> parseString(c)
            else -> throw kotlin.IllegalArgumentException("Unexpected char: $c")
        }
    }

    /** i<digits>e — forbids illegal leading zeros (except "0" / "-0"). */
    private fun parseInt(): BencodeInt {
        val sb = StringBuilder()
        var c = input.read()
        if (c == '-'.code) {
            sb.append('-')
            c = input.read()
        }
        require(c in '0'.code..'9'.code) { "Invalid integer: expected digit, got '${c.toChar()}'" }
        while (c != 'e'.code) {
            require(c != -1) { "Unexpected EOF in integer" }
            require(c in '0'.code..'9'.code) { "Invalid integer char '${c.toChar()}'" }
            sb.append(c.toChar())
            c = input.read()
        }
        val s = sb.toString()
        val core = if (s.startsWith('-')) s.drop(1) else s
        require(core == "0" || !core.startsWith("0")) { "Leading zeros not allowed in integer: $s" }
        return BencodeInt(s.toLong())
    }

    /** <len>:<bytes> — reads exactly len bytes, errors on short read. */
    private fun parseString(firstChar: Char): BencodeString {
        val lenStr = StringBuilder().apply {
            append(firstChar)
            var c = input.read() // we already consumed peek() above
            while (c in '0'.code..'9'.code) {
                append(c.toChar())
                c = input.read()
            }
            require(c == ':'.code) { "Expected ':' after string length, got '${c.toChar()}'" }
        }.toString()

        val len = lenStr.toIntOrNull() ?: error("Invalid string length: '$lenStr'")
        require(len >= 0) { "Negative string length: $len" }

        val bytes = ByteArray(len)
        val readN = input.read(bytes)
        require(readN == len) { "Unexpected EOF in string (wanted $len, got $readN)" }
        return BencodeString(bytes)
    }

    private fun parseList(): BencodeList {
        val list = mutableListOf<BencodeValue>()
        while (true) {
            val mark = input.mark(1)
            val c = input.read()
            if (c == 'e'.code) break
            input.reset()
            list += parseValue()
        }
        return BencodeList(list)
    }

    private fun parseDict(): BencodeDict {
        val map = mutableMapOf<String, BencodeValue>()
        while (true) {
            val mark = input.mark(1)
            val c = input.read()
            if (c == 'e'.code) break
            input.reset()
            val key = (parseValue() as BencodeString).asUtf8()
            val value = parseValue()
            map[key] = value
        }
        return BencodeDict(map)
    }
}

fun parseBencodeFile(file: File): BencodeValue {
    file.inputStream().use { return BencodeParser(it).parse() }
}

/**
 * Encode any BencodeValue to canonical bytes.
 * Uses a streaming encoder to avoid repeated ByteArray concatenations.
 */
fun bencode(value: BencodeValue): ByteArray {
    val out = ByteArrayOutputStream()

    fun writeAscii(s: String) = out.write(s.toByteArray(StandardCharsets.US_ASCII))

    fun encode(v: BencodeValue) {
        when (v) {
            is BencodeInt -> {
                out.write('i'.code); writeAscii(v.value.toString()); out.write('e'.code)
            }
            is BencodeString -> {
                writeAscii(v.value.size.toString()); out.write(':'.code); out.write(v.value)
            }
            is BencodeList -> {
                out.write('l'.code)
                v.value.forEach(::encode)
                out.write('e'.code)
            }
            is BencodeDict -> {
                out.write('d'.code)
                // Spec requires keys sorted lexicographically by *raw bytes*.
                // Keys are Strings here; sort by their UTF-8 bytes to match bencode ordering.
                val sortedKeys = v.value.keys.sortedWith { a, b ->
                    val ab = a.toByteArray(StandardCharsets.UTF_8)
                    val bb = b.toByteArray(StandardCharsets.UTF_8)
                    val n = minOf(ab.size, bb.size)
                    var i = 0
                    while (i < n) {
                        val ai = ab[i].toInt() and 0xFF
                        val bi = bb[i].toInt() and 0xFF
                        if (ai != bi) return@sortedWith ai - bi
                        i++
                    }
                    ab.size - bb.size
                }
                for (k in sortedKeys) {
                    val kb = k.toByteArray(StandardCharsets.UTF_8)
                    writeAscii(kb.size.toString()); out.write(':'.code); out.write(kb)
                    encode(v.value.getValue(k))
                }
                out.write('e'.code)
            }
        }
    }

    encode(value)
    return out.toByteArray()
}
