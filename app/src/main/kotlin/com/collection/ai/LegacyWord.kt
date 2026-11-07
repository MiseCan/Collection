package com.collection.ai

import org.apache.poi.hwpf.HWPFDocument
import org.apache.poi.hwpf.OldWordFileFormatException
import org.apache.poi.hwpf.extractor.Word6Extractor
import org.apache.poi.hwpf.usermodel.Range
import org.apache.poi.poifs.filesystem.POIFSFileSystem
import java.io.File

/** Read the main Word story without converting or writing the original container. */
internal fun readLegacyWordBody(file: File): String = POIFSFileSystem(file, true).use { filesystem ->
    val body = try {
        HWPFDocument(filesystem.root).range.text()
    } catch (_: OldWordFileFormatException) {
        // The replacement getText() uses POI's desktop document converter.
        @Suppress("DEPRECATION")
        Word6Extractor(filesystem.root).paragraphText.joinToString("\n")
    }
    Range.stripFields(body)
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .replace('\u000b', '\n')
        .replace('\u000c', '\n')
        .replace('\u0007', '\t')
        .filter { it == '\t' || it == '\n' || !it.isISOControl() }
}
