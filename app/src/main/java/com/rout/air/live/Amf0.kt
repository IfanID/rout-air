package com.rout.air.live

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * Encoder/decoder AMF0 minimal untuk perintah RTMP.
 *
 * Nilai yang didukung: number (Double), boolean, string, null, object
 * (Map<String, Any?>), ECMA array, dan strict array. Tipe lain dilewati
 * sebagai null supaya paket dari server tidak membuat proses berhenti.
 */
object Amf0 {
    private const val NUMBER = 0x00
    private const val BOOLEAN = 0x01
    private const val STRING = 0x02
    private const val OBJECT = 0x03
    private const val NULL = 0x05
    private const val ECMA_ARRAY = 0x08
    private const val OBJECT_END = 0x09
    private const val STRICT_ARRAY = 0x0A
    private const val LONG_STRING = 0x0C

    fun encode(values: List<Any?>): ByteArray {
        val out = ByteArrayOutputStream()
        values.forEach { writeValue(out, it) }
        return out.toByteArray()
    }

    fun decode(data: ByteArray): List<Any?> {
        val reader = Reader(data)
        val values = mutableListOf<Any?>()
        while (reader.hasRemaining()) {
            values += reader.readValue()
        }
        return values
    }

    private fun writeValue(out: ByteArrayOutputStream, value: Any?) {
        when (value) {
            null -> out.write(NULL)
            is Boolean -> {
                out.write(BOOLEAN)
                out.write(if (value) 1 else 0)
            }

            is Number -> {
                out.write(NUMBER)
                writeDouble(out, value.toDouble())
            }

            is String -> writeString(out, value)
            is Map<*, *> -> writeObject(out, value)
            is List<*> -> writeStrictArray(out, value)
            // Tipe tak dikenal dikirim sebagai null agar stream tetap valid.
            else -> out.write(NULL)
        }
    }

    private fun writeObject(out: ByteArrayOutputStream, map: Map<*, *>) {
        out.write(OBJECT)
        map.forEach { (key, value) ->
            val name = key?.toString() ?: return@forEach
            writeUtf8(out, name)
            writeValue(out, value)
        }
        out.write(0)
        out.write(0)
        out.write(OBJECT_END)
    }

    private fun writeStrictArray(out: ByteArrayOutputStream, values: List<*>) {
        out.write(STRICT_ARRAY)
        writeInt(out, values.size)
        values.forEach { writeValue(out, it) }
    }

    private fun writeString(out: ByteArrayOutputStream, value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        if (bytes.size > 0xFFFF) {
            out.write(LONG_STRING)
            writeInt(out, bytes.size)
        } else {
            out.write(STRING)
            writeShort(out, bytes.size)
        }
        out.write(bytes, 0, bytes.size)
    }

    private fun writeUtf8(out: ByteArrayOutputStream, value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        writeShort(out, bytes.size)
        out.write(bytes, 0, bytes.size)
    }

    private fun writeShort(out: ByteArrayOutputStream, value: Int) {
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    private fun writeInt(out: ByteArrayOutputStream, value: Int) {
        out.write((value ushr 24) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    private fun writeDouble(out: ByteArrayOutputStream, value: Double) {
        val bits = java.lang.Double.doubleToLongBits(value)
        for (shift in 56 downTo 0 step 8) {
            out.write(((bits ushr shift) and 0xFF).toInt())
        }
    }

    private class Reader(private val data: ByteArray) {
        private var position = 0

        fun hasRemaining(): Boolean = position < data.size

        fun readValue(): Any? {
            if (!hasRemaining()) return null
            return when (val type = readByte()) {
                NUMBER -> readDouble()
                BOOLEAN -> readByte() != 0
                STRING -> readUtf8(readShort())
                LONG_STRING -> readUtf8(readInt())
                OBJECT -> readObject()
                ECMA_ARRAY -> readEcmaArray()
                STRICT_ARRAY -> readStrictArray()
                NULL, 0x06 -> null
                else -> {
                    // Tipe tidak dikenal: hentikan agar tidak salah baca.
                    position = data.size
                    null
                }
            }
        }

        private fun readObject(): Map<String, Any?> {
            val map = LinkedHashMap<String, Any?>()
            while (hasRemaining()) {
                val nameLength = readShort()
                if (nameLength == 0) {
                    if (hasRemaining() && readByte() == OBJECT_END) break
                    continue
                }
                val name = readUtf8(nameLength)
                map[name] = readValue()
            }
            return map
        }

        private fun readEcmaArray(): Map<String, Any?> {
            readInt() // jumlah entri; tidak selalu akurat
            return readObject()
        }

        private fun readStrictArray(): List<Any?> {
            val count = readInt().coerceIn(0, 1024)
            return List(count) { readValue() }
        }

        private fun readUtf8(length: Int): String {
            val end = (position + length).coerceAtMost(data.size)
            val text = String(data, position, end - position, StandardCharsets.UTF_8)
            position = end
            return text
        }

        private fun readShort(): Int {
            if (position + 2 > data.size) {
                position = data.size
                return 0
            }
            return ((data[position++].toInt() and 0xFF) shl 8) or
                (data[position++].toInt() and 0xFF)
        }

        private fun readInt(): Int {
            if (position + 4 > data.size) {
                position = data.size
                return 0
            }
            return ((data[position++].toInt() and 0xFF) shl 24) or
                ((data[position++].toInt() and 0xFF) shl 16) or
                ((data[position++].toInt() and 0xFF) shl 8) or
                (data[position++].toInt() and 0xFF)
        }

        private fun readDouble(): Double {
            if (position + 8 > data.size) {
                position = data.size
                return 0.0
            }
            var bits = 0L
            repeat(8) {
                bits = (bits shl 8) or (data[position++].toLong() and 0xFF)
            }
            return java.lang.Double.longBitsToDouble(bits)
        }

        private fun readByte(): Int {
            if (!hasRemaining()) return 0
            return data[position++].toInt() and 0xFF
        }
    }
}
