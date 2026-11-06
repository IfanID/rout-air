package com.rout.air.encode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.rout.air.encode.video.OutputTarget

/** Nama berkas dipakai juga saat memulihkan rekaman aman. */
class OutputNameTest {
    private val stampMs = 1_700_000_000_000L

    private fun stamp(pattern: String): String =
        SimpleDateFormat(pattern, Locale.US).format(Date(stampMs))

    @Test
    fun templatePlaceholdersAreFilled() {
        val name = OutputTarget.buildName(
            timestampMs = stampMs,
            nameTemplate = "Rekaman {date} {time}",
            noSpaceFileName = false,
        )
        assertEquals("Rekaman ${stamp("yyyy-MM-dd")} ${stamp("HH-mm-ss")}", name)
    }

    @Test
    fun fixedNameGetsUniqueStamp() {
        val name = OutputTarget.buildName(
            timestampMs = stampMs,
            nameTemplate = "Proyek",
            noSpaceFileName = false,
        )
        assertEquals("Proyek_${stamp("yyyyMMdd_HHmmss")}", name)
    }

    @Test
    fun spacesCanBeReplaced() {
        val name = OutputTarget.buildName(
            timestampMs = stampMs,
            nameTemplate = "Rekaman {datetime}",
            noSpaceFileName = true,
        )
        assertTrue(name.none { it == ' ' })
    }
}
