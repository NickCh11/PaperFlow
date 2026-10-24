package com.example.myapplication;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import androidx.exifinterface.media.ExifInterface;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import static org.junit.Assert.*;

public class IdPhotoExportRegressionTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private void main(Runnable action) { InstrumentationRegistry.getInstrumentation().runOnMainSync(action); }
    private Bitmap portrait() { Bitmap source = Bitmap.createBitmap(100, 160, Bitmap.Config.ARGB_8888); source.eraseColor(Color.GREEN); return source; }
    private IdPhotoMakerActivity.PhotoSession session() {
        IdPhotoMakerActivity.PhotoSession session = new IdPhotoMakerActivity.PhotoSession((Application)context.getApplicationContext());
        main(() -> { session.source = portrait(); session.background = 0; }); return session;
    }
    private void close(IdPhotoMakerActivity.PhotoSession session) throws Exception {
        main(session::onCleared); assertTrue(session.worker.awaitTermination(5, TimeUnit.SECONDS));
        if (session.source != null) session.source.recycle(); if (session.foreground != null) session.foreground.recycle();
    }

    @Test public void jpegUsesPhysicalDimensionsAndThreeHundredDpiAndCopiesExactBytes() throws Exception {
        IdPhotoMakerActivity.PhotoSession session = session(); File destination = File.createTempFile("id-photo-copy-", ".jpg", context.getCacheDir()); File output = null;
        try {
            main(session::export); UiTestWait.until(() -> !session.busy && session.output != null, "ID photo JPEG did not export"); output = session.output;
            assertEquals("image/jpeg", session.mime); Bitmap image = BitmapFactory.decodeFile(output.getPath());
            try { assertEquals(413, image.getWidth()); assertEquals(531, image.getHeight()); assertTrue(Color.green(image.getPixel(200, 250)) > 240); assertTrue(Color.red(image.getPixel(200, 250)) < 15); }
            finally { image.recycle(); }
            ExifInterface exif = new ExifInterface(output); assertEquals(300, exif.getAttributeDouble(ExifInterface.TAG_X_RESOLUTION, 0), .01); assertEquals(300, exif.getAttributeDouble(ExifInterface.TAG_Y_RESOLUTION, 0), .01); assertEquals(2, exif.getAttributeInt(ExifInterface.TAG_RESOLUTION_UNIT, 0));
            byte[] expected = Files.readAllBytes(output.toPath()); main(() -> session.copyOutput(Uri.fromFile(destination)));
            UiTestWait.until(() -> !session.busy, "ID photo copy did not finish"); assertArrayEquals(expected, Files.readAllBytes(destination.toPath()));
        } finally { close(session); if (output != null) output.delete(); destination.delete(); }
    }

    @Test public void printSheetsUseTheRequestedPaperSizeAndContainThePortrait() throws Exception {
        IdPhotoMakerActivity.PhotoSession session = session(); ArrayList<File> outputs = new ArrayList<>(); PDFBoxResourceLoader.init(context);
        try {
            for (int layout : new int[]{1, 2}) {
                main(() -> { session.layout = layout; session.output = null; session.export(); });
                UiTestWait.until(() -> !session.busy && session.output != null, "ID photo print sheet did not export"); outputs.add(session.output);
                assertEquals("application/pdf", session.mime);
                try (PDDocument pdf = PDDocument.load(session.output)) {
                    assertEquals(1, pdf.getNumberOfPages()); assertEquals(layout == 1 ? 288 : 595, pdf.getPage(0).getMediaBox().getWidth(), 1); assertEquals(layout == 1 ? 432 : 842, pdf.getPage(0).getMediaBox().getHeight(), 1);
                    Bitmap rendered = new com.tom_roush.pdfbox.rendering.PDFRenderer(pdf).renderImage(0, .5f);
                    try { int green = 0; for (int y = 0; y < rendered.getHeight(); y++) for (int x = 0; x < rendered.getWidth(); x++) { int pixel = rendered.getPixel(x, y); if (Color.green(pixel) > 240 && Color.red(pixel) < 15) green++; } assertTrue("Print sheet did not contain the portrait", green > 1000); assertEquals(Color.WHITE, rendered.getPixel(0, 0)); }
                    finally { rendered.recycle(); }
                }
            }
        } finally { close(session); for (File file : outputs) file.delete(); }
    }

    @Test public void framingSurvivesRecreationAndSavedStateKeepsThePhysicalSize() throws Exception {
        AtomicReference<IdPhotoMakerActivity.PhotoSession> active = new AtomicReference<>();
        try (ActivityScenario<IdPhotoMakerActivity> scenario = ActivityScenario.launch(new Intent(context, IdPhotoMakerActivity.class))) {
            scenario.onActivity(a -> { IdPhotoMakerActivity.PhotoSession session = new androidx.lifecycle.ViewModelProvider(a).get(IdPhotoMakerActivity.PhotoSession.class); active.set(session); session.source = portrait(); session.mmWidth = 35; session.mmHeight = 45; session.rotation = 90; session.magnification = 2; session.panX = 100; session.panY = -100; session.constrainPan(); assertTrue(Math.abs(session.panX) < 100); assertTrue(Math.abs(session.panY) < 100); session.publish(); });
            scenario.recreate();
            scenario.onActivity(a -> {
                IdPhotoMakerActivity.PhotoSession retained = new androidx.lifecycle.ViewModelProvider(a).get(IdPhotoMakerActivity.PhotoSession.class); assertSame(active.get(), retained); assertEquals(90, retained.rotation); assertEquals(2, retained.magnification, 0);
                Bundle state = new Bundle(); retained.save(state); IdPhotoMakerActivity.PhotoSession restored = new IdPhotoMakerActivity.PhotoSession((Application)context.getApplicationContext());
                try { restored.restore(state); assertEquals(35, restored.mmWidth, 0); assertEquals(45, restored.mmHeight, 0); assertEquals(retained.panX, restored.panX, 0); assertEquals(retained.panY, restored.panY, 0); assertEquals("35 × 45 mm", restored.dimensionLabel()); assertTrue(restored.summary().contains("300 DPI")); }
                finally { restored.onCleared(); }
            });
        } finally { if (active.get() != null && active.get().source != null) active.get().source.recycle(); }
    }
}
