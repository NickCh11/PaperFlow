/*
 * Copyright 2025-2026 The FairScan authors
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version.
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <https://www.gnu.org/licenses/>.
 */
// Modified 2026-10-21: extracted text writer and isolated appended graphics state for PaperFlow.
package com.paperflow.scanengine
import android.content.res.AssetManager
import com.tom_roush.pdfbox.cos.*
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.*
import com.tom_roush.pdfbox.pdmodel.font.PDFontDescriptor
import com.paperflow.imageprocessing.OcrTextBox
import java.util.Locale
data class PageDimensions(
    val imageWidth: Int,
    val imageHeight: Int,
    val pageWidth: Float,
    val pageHeight: Float,
)

// Adapted from https://github.com/tesseract-ocr/tesseract/blob/main/src/api/pdfrenderer.cpp
class OcrDocument(
    private val document: PDDocument,
    private val assets: AssetManager,
) {
    private val fontBytes: ByteArray by lazy {
        assets.open("fonts/TesseractGlyphLessFont.ttf").use { it.readBytes() }
    }

    private val cidToGidMap: ByteArray by lazy {
        ByteArray(65536 * 2) { i -> if (i % 2 == 0) 0x00.toByte() else 0x01.toByte() }
    }

    private var fontDict: COSDictionary? = null
    private var unicodeStream: PDStream? = null
    // Identity-H encodes 16-bit character IDs, not UTF-16 code units. One ID
    // maps to one complete Unicode scalar, including a supplementary pair.
    private val characterCodes = linkedMapOf(0x20 to 1)

    fun ensureResourcesAreCreated() {
        if (fontDict != null)
            return

        // -- Object 1 : embedded TTF --
        val fontFileStream = PDStream(document)
        fontFileStream.createOutputStream().use { it.write(fontBytes) }
        fontFileStream.cosObject.setInt(COSName.LENGTH1, fontBytes.size)

        // -- Object 2 : CIDToGIDMap stream --
        val cidToGidStream = PDStream(document)
        cidToGidStream.createOutputStream(COSName.FLATE_DECODE).use {
            it.write(cidToGidMap)
        }

        // -- Object 3 : FontDescriptor --
        val fontDescriptor = PDFontDescriptor(COSDictionary())
        fontDescriptor.fontName = "GlyphLessFont"
        fontDescriptor.flags = 5
        fontDescriptor.fontBoundingBox = PDRectangle(0f, 0f, 500f, 750f)
        fontDescriptor.italicAngle = 0f
        fontDescriptor.ascent = 750f
        fontDescriptor.descent = 0f
        fontDescriptor.capHeight = 750f
        fontDescriptor.stemV = 80f
        fontDescriptor.cosObject.setItem(COSName.FONT_FILE2, fontFileStream)

        // -- Object 4 : CIDFont descendant --
        val cidFont = COSDictionary()
        cidFont.setName(COSName.TYPE, "Font")
        cidFont.setName(COSName.SUBTYPE, "CIDFontType2")
        cidFont.setName(COSName.BASE_FONT, "GlyphLessFont")
        val cidSystemInfo = COSDictionary()
        cidSystemInfo.setString(COSName.getPDFName("Registry"), "Adobe")
        cidSystemInfo.setString(COSName.getPDFName("Ordering"), "Identity")
        cidSystemInfo.setInt(COSName.getPDFName("Supplement"), 0)
        cidFont.setItem(COSName.getPDFName("CIDSystemInfo"), cidSystemInfo)
        cidFont.setItem(COSName.FONT_DESC, fontDescriptor)
        cidFont.setInt(COSName.getPDFName("DW"), 500)
        cidFont.setItem(COSName.getPDFName("CIDToGIDMap"), cidToGidStream)

        // -- Object 5 : ToUnicode CMap --
        val toUnicode = buildToUnicodeCMap()
        val toUnicodeStream = PDStream(document)
        unicodeStream = toUnicodeStream
        toUnicodeStream.createOutputStream().use {
            it.write(toUnicode.toByteArray(Charsets.US_ASCII))
        }

        // -- Object 6 : Font Type0 --
        val fontDict = COSDictionary()
        fontDict.setName(COSName.TYPE, "Font")
        fontDict.setName(COSName.SUBTYPE, "Type0")
        fontDict.setName(COSName.BASE_FONT, "GlyphLessFont")
        fontDict.setName(COSName.ENCODING, "Identity-H")
        val descendants = COSArray()
        descendants.add(cidFont)
        fontDict.setItem(COSName.DESCENDANT_FONTS, descendants)
        fontDict.setItem(COSName.TO_UNICODE, toUnicodeStream)
        this.fontDict = fontDict
    }

    private fun buildToUnicodeCMap(): String = buildString {
        append("/CIDInit /ProcSet findresource begin\n")
        append("12 dict begin\n")
        append("begincmap\n")
        append("/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n")
        append("/CMapName /PaperFlow-OCR-UCS def\n")
        append("/CMapType 2 def\n")
        append("1 begincodespacerange\n")
        append("<0000> <FFFF>\n")
        append("endcodespacerange\n")
        for (batch in characterCodes.entries.chunked(100)) {
            append("${batch.size} beginbfchar\n")
            for ((codepoint, code) in batch) {
                append("<%04X> <%s>\n".format(Locale.US, code, codepointToUtf16beHex(codepoint)))
            }
            append("endbfchar\n")
        }
        append("endcmap\n")
        append("CMapName currentdict /CMap defineresource pop\n")
        append("end\nend\n")
    }

    fun addPage(
        page: PDPage,
        ocrTextBoxes: List<OcrTextBox>,
        dimensions: PageDimensions,
    ) {
        if (ocrTextBoxes.isEmpty()) return

        ensureResourcesAreCreated()
        val resources = page.resources ?: PDResources().also { page.resources = it }
        val fontResources = resources.cosObject
            .getDictionaryObject(COSName.FONT) as? COSDictionary
            ?: COSDictionary().also {
                resources.cosObject.setItem(COSName.FONT, it)
            }
        // Each writer assigns its own character IDs. Keep existing mappings
        // intact when another OCR pass appends text, including shared resources.
        var suffix = 0
        var fontName = COSName.getPDFName("PF_OCR_GLYPHLESS")
        while (fontResources.containsKey(fontName) && fontResources.getDictionaryObject(fontName) !== fontDict) {
            suffix++
            fontName = COSName.getPDFName("PF_OCR_GLYPHLESS_$suffix")
        }
        fontResources.setItem(fontName, fontDict)

        val textStream = "q\n1 0 0 1 ${page.cropBox.lowerLeftX} ${page.cropBox.lowerLeftY} cm\n" + buildTextStream(ocrTextBoxes, dimensions, fontName.name) + "Q\n"
        unicodeStream!!.createOutputStream().use {
            it.write(buildToUnicodeCMap().toByteArray(Charsets.US_ASCII))
        }
        com.tom_roush.pdfbox.pdmodel.PDPageContentStream(document, page,
            com.tom_roush.pdfbox.pdmodel.PDPageContentStream.AppendMode.APPEND, true, true).use {
            it.appendRawCommands(textStream)
        }
    }

    private fun buildTextStream(
        ocrTextBoxes: List<OcrTextBox>,
        dimensions: PageDimensions,
        fontName: String,
    ): String {
        val scaleX = dimensions.pageWidth / dimensions.imageWidth
        val scaleY = dimensions.pageHeight / dimensions.imageHeight
        val sb = StringBuilder()

        for (textBox in ocrTextBoxes) {
            val x = textBox.box.left * scaleX
            val wordWidth = textBox.box.width * scaleX
            val fontSize = textBox.lineHeight * scaleY * 0.8f
            // nominal width: fontSize / kCharWidth (Tesseract convention)
            // Include the explicit separator in the fitted width. Otherwise it
            // overlaps the next word and PDF readers can sort it inside that word.
            val nominalWidth = (textBox.text.codePointCount(0, textBox.text.length) + 1) * fontSize / 2f
            val hScale = if (nominalWidth > 0f) (wordWidth / nominalWidth) * 100f else 100f
            val baselineY = dimensions.pageHeight - (textBox.lineBottom * scaleY) + fontSize * 0.2f

            val encoded = buildString {
                textBox.text.codePoints().forEach { cp ->
                    val code = characterCodes.getOrPut(cp) {
                        require(characterCodes.size < 65535) { "Too many distinct OCR characters for one PDF font" }
                        characterCodes.size + 1
                    }
                    append("%04X".format(Locale.US, code))
                }
            }

            sb.append("BT\n")
            sb.append(String.format(Locale.US, "/%s %.3f Tf\n", fontName, fontSize))
            sb.append(String.format(Locale.US,"%.3f Tz\n", hScale))
            sb.append("3 Tr\n")
            sb.append(String.format(Locale.US,"%.3f %.3f Td\n", x, baselineY))
            sb.append("<${encoded}0001> Tj\n")
            sb.append("ET\n")
        }
        return sb.toString()
    }

    private fun codepointToUtf16beHex(cp: Int): String {
        return if (cp < 0x10000) {
            "%04X".format(cp)
        } else {
            val a = cp - 0x10000
            val high = (a shr 10) + 0xD800
            val low = (a and 0x3FF) + 0xDC00
            "%04X%04X".format(high, low)
        }
    }
}
