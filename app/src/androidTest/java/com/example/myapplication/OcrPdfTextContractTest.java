package com.example.myapplication;

import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import com.paperflow.imageprocessing.ImageRect;
import com.paperflow.imageprocessing.OcrTextBox;
import com.paperflow.scanengine.OcrDocument;
import com.paperflow.scanengine.PageDimensions;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.cos.*;
import com.tom_roush.pdfbox.pdmodel.*;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class OcrPdfTextContractTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final PageDimensions dimensions = new PageDimensions(600, 800, 300, 400);
    @Before public void initialize() { PDFBoxResourceLoader.init(context); }
    private OcrDocument writer(PDDocument document) { return new OcrDocument(document, context.getAssets()); }
    private OcrTextBox box(String text) { return new OcrTextBox(text, new ImageRect(20, 40, 500, 90), 50, 90); }
    private PDPage page(PDDocument document) { PDPage page = new PDPage(new PDRectangle(300, 400)); document.addPage(page); return page; }
    private byte[] save(PDDocument document) throws Exception { ByteArrayOutputStream output = new ByteArrayOutputStream(); document.save(output); return output.toByteArray(); }

    @Test public void multilingualAndSupplementaryCharactersSurvivePdfSaveAndReload() throws Exception {
        String text = "PaperFlow Ελληνικά 日本語 \uD83D\uDE03 \uD840\uDC00";
        try (PDDocument document = new PDDocument()) {
            PDPage page = page(document); PDRectangle crop = new PDRectangle(10, 15, 270, 360); page.setCropBox(crop);
            writer(document).addPage(page, Collections.singletonList(box(text)), dimensions);
            try (PDDocument reloaded = PDDocument.load(save(document))) {
                assertTrue(new PDFTextStripper().getText(reloaded), new PDFTextStripper().getText(reloaded).contains(text));
                assertEquals(crop.getLowerLeftX(), reloaded.getPage(0).getCropBox().getLowerLeftX(), 0);
                assertEquals(crop.getLowerLeftY(), reloaded.getPage(0).getCropBox().getLowerLeftY(), 0);
            }
        }
    }

    @Test public void emptyRecognitionAddsNoContentOrFontResources() throws Exception {
        try (PDDocument document = new PDDocument()) {
            PDPage page = page(document); assertNull(page.getResources());
            writer(document).addPage(page, Collections.emptyList(), dimensions);
            assertNull(page.getResources()); assertFalse(page.hasContents());
        }
    }

    @Test public void mappingBatchesAndNewCharactersOnLaterPagesPreserveEarlierText() throws Exception {
        StringBuilder first = new StringBuilder(), second = new StringBuilder();
        for (int i = 0; i < 220; i++) first.appendCodePoint(0x4E00 + i);
        for (int i = 0; i < 120; i++) second.appendCodePoint(0x20000 + i);
        try (PDDocument document = new PDDocument()) {
            OcrDocument writer = writer(document);
            writer.addPage(page(document), Collections.singletonList(box(first.toString())), dimensions);
            writer.addPage(page(document), Collections.singletonList(box(second.toString())), dimensions);
            try (PDDocument reloaded = PDDocument.load(save(document))) {
                String text = new PDFTextStripper().getText(reloaded);
                assertTrue(text, text.contains(first)); assertTrue(text, text.contains(second));
            }
        }
    }

    @Test public void anExhaustedCharacterIdSpaceFailsBeforeWritingAMalformedPage() throws Exception {
        StringBuilder text = new StringBuilder(); int codepoint = 0x21;
        for (int count = 0; count < 65535; count++, codepoint++) {
            if (codepoint == 0xD800) codepoint = 0xE000;
            text.appendCodePoint(codepoint);
        }
        try (PDDocument document = new PDDocument()) {
            PDPage page = page(document);
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> writer(document).addPage(page, Collections.singletonList(box(text.toString())), dimensions));
            assertTrue(error.getMessage().contains("Too many distinct OCR characters"));
            assertFalse(page.hasContents());
        }
    }

    @Test public void appendedOcrPreservesExistingTextFontsAndReusesItsOwnFont() throws Exception {
        try (PDDocument document = new PDDocument()) {
            PDPage first = page(document), second = page(document);
            try (PDPageContentStream output = new PDPageContentStream(document, first)) {
                output.beginText(); output.setFont(PDType1Font.HELVETICA, 12); output.newLineAtOffset(20, 250); output.showText("Original visible text"); output.endText();
            }
            COSDictionary fonts = (COSDictionary) first.getResources().getCOSObject().getDictionaryObject(COSName.FONT);
            Set<COSName> original = new HashSet<>(fonts.keySet()); OcrDocument writer = writer(document);
            writer.addPage(first, Collections.singletonList(box("Added OCR")), dimensions);
            writer.ensureResourcesAreCreated(); writer.addPage(second, Collections.singletonList(box("Second page")), dimensions);
            COSName name = COSName.getPDFName("PF_OCR_GLYPHLESS");
            assertTrue(fonts.keySet().containsAll(original));
            assertSame(fonts.getDictionaryObject(name), ((COSDictionary) second.getResources().getCOSObject().getDictionaryObject(COSName.FONT)).getDictionaryObject(name));
            try (PDDocument reloaded = PDDocument.load(save(document))) {
                String text = new PDFTextStripper().getText(reloaded);
                for (String expected : new String[]{"Original visible text", "Added OCR", "Second page"}) assertTrue(text, text.contains(expected));
            }
        }
    }

    @Test public void malformedFontResourcesAreReplacedAndZeroHeightBoxesStayFinite() throws Exception {
        try (PDDocument document = new PDDocument()) {
            PDPage page = page(document); page.setResources(new PDResources()); page.getResources().getCOSObject().setString(COSName.FONT, "invalid font dictionary");
            writer(document).addPage(page, Arrays.asList(new OcrTextBox("", new ImageRect(0, 0, 0, 0), 0, 0), box("Retained")), dimensions);
            assertTrue(page.getResources().getCOSObject().getDictionaryObject(COSName.FONT) instanceof COSDictionary);
            ByteArrayOutputStream content = new ByteArrayOutputStream();
            try (InputStream input = page.getContents()) { byte[] buffer = new byte[1024]; int count; while ((count = input.read(buffer)) != -1) content.write(buffer, 0, count); }
            String commands = content.toString(StandardCharsets.US_ASCII.name());
            assertFalse(commands, commands.contains("NaN")); assertFalse(commands, commands.contains("Infinity")); assertTrue(commands, commands.contains("100.000 Tz"));
            try (PDDocument reloaded = PDDocument.load(save(document))) { assertTrue(new PDFTextStripper().getText(reloaded).contains("Retained")); }
        }
    }

    @Test public void independentOcrPassesPreserveEarlierCharacterMapsAfterReload() throws Exception {
        byte[] firstPass;
        try(PDDocument document=new PDDocument()) {
            writer(document).addPage(page(document),Collections.singletonList(box("First Ω \uD83D\uDE03")),dimensions);
            firstPass=save(document);
        }
        try(PDDocument document=PDDocument.load(firstPass)) {
            PDPage page=document.getPage(0);COSName originalName=COSName.getPDFName("PF_OCR_GLYPHLESS");
            COSDictionary fonts=(COSDictionary)page.getResources().getCOSObject().getDictionaryObject(COSName.FONT);
            Object originalMap=fonts.getDictionaryObject(originalName);
            OcrDocument second=writer(document);
            second.addPage(page,Collections.singletonList(new OcrTextBox("Later 日本語 \uD840\uDC00",new ImageRect(20,200,500,250),50,250)),dimensions);
            second.addPage(page,Collections.singletonList(new OcrTextBox("More Ελληνικά",new ImageRect(20,360,500,410),50,410)),dimensions);
            assertSame("A new OCR pass replaced the earlier font",originalMap,fonts.getDictionaryObject(originalName));
            assertEquals("Repeated pages from one writer reuse its font",2,fonts.size());
            try(PDDocument reloaded=PDDocument.load(save(document))) {
                String text=new PDFTextStripper().getText(reloaded);
                for(String expected:new String[]{"First Ω \uD83D\uDE03","Later 日本語 \uD840\uDC00","More Ελληνικά"})assertTrue(text,text.contains(expected));
            }
        }
    }

    @Test public void sharedPageResourcesRetainEveryIndependentOcrFont() throws Exception {
        try(PDDocument document=new PDDocument()) {
            PDPage first=page(document),second=page(document);PDResources shared=new PDResources();first.setResources(shared);second.setResources(shared);
            writer(document).addPage(first,Collections.singletonList(box("Alphabet ABC")),dimensions);
            writer(document).addPage(second,Collections.singletonList(box("Ελληνικά Ω")),dimensions);
            writer(document).addPage(first,Collections.singletonList(new OcrTextBox("日本語 \uD840\uDC00",new ImageRect(20,200,500,250),50,250)),dimensions);
            COSDictionary fonts=(COSDictionary)shared.getCOSObject().getDictionaryObject(COSName.FONT);
            assertEquals("Every writer needs its own mapping even with shared resources",3,fonts.size());
            try(PDDocument reloaded=PDDocument.load(save(document))) {
                String text=new PDFTextStripper().getText(reloaded);
                for(String expected:new String[]{"Alphabet ABC","Ελληνικά Ω","日本語 \uD840\uDC00"})assertTrue(text,text.contains(expected));
            }
        }
    }
}
