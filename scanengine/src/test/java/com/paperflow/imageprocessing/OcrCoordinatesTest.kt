package com.paperflow.imageprocessing

import org.junit.Assert.*
import org.junit.Test

class OcrCoordinatesTest {
    @Test fun imageCornersMapToPdfPointsWithTheVerticalAxisInverted() {
        val converter = OcrCoordinateConverter(1200, 2000, 600f, 1000f)
        assertEquals(PdfRect(0f, 0f, 600f, 1000f), converter.convert(ImageRect(0, 0, 1200, 2000)))
        assertEquals(PdfRect(50f, 800f, 150f, 100f), converter.convert(ImageRect(100, 200, 400, 400)))
        assertEquals(PdfRect(600f, 0f, 0f, 0f), converter.convert(ImageRect(1200, 2000, 1200, 2000)))
    }

    @Test fun nonuniformPageScalingPreservesIndependentAxesAndLineMetadata() {
        val box = ImageRect(20, 30, 120, 80)
        assertEquals(100, box.width); assertEquals(50, box.height)
        val text = OcrTextBox("Ελληνικά 日本語", box, 25, 77)
        assertEquals(box, text.box); assertEquals(25, text.lineHeight); assertEquals(77, text.lineBottom)
        assertEquals("Ελληνικά 日本語", text.text)
        val converted = OcrCoordinateConverter(200, 100, 1000f, 200f).convert(box)
        assertEquals(100f, converted.x, 0f); assertEquals(40f, converted.y, 0f)
        assertEquals(500f, converted.width, 0f); assertEquals(100f, converted.height, 0f)
        assertEquals(text, text.copy()); assertEquals(box, box.copy()); assertEquals(converted, converted.copy())
        assertEquals(Line(Point(0, 0), Point(3, 4)), Line(Point(0, 0), Point(3, 4)).copy())
    }
}
