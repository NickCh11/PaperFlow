package com.example.myapplication;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.*;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import java.io.File;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import static org.junit.Assert.*;

public class LongImageRegressionTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private void main(Runnable action) { InstrumentationRegistry.getInstrumentation().runOnMainSync(action); }
    private File document() throws Exception {
        PDFBoxResourceLoader.init(context); File input = File.createTempFile("long-image-test-", ".pdf", context.getCacheDir());
        try (PDDocument pdf = new PDDocument()) {
            int[][] colors = {{255, 0, 0}, {0, 255, 0}, {0, 0, 255}};
            for (int i = 0; i < colors.length; i++) {
                PDPage page = new PDPage(new PDRectangle(200, 300)); if (i == 2) page.setRotation(90); pdf.addPage(page);
                try (PDPageContentStream stream = new PDPageContentStream(pdf, page)) { int[] rgb = colors[i]; stream.setNonStrokingColor(rgb[0], rgb[1], rgb[2]); stream.addRect(0, 0, 200, 300); stream.fill(); }
            }
            pdf.save(input);
        }
        return input;
    }

    @Test public void layoutRespectsRotationQualityAndTheReadableHeightLimit() throws Exception {
        File input = document();
        try (PDDocument pdf = PDDocument.load(input)) {
            List<float[]> sizes = new ArrayList<>(); for (PDPage page : pdf.getPages()) sizes.add(LongImageExporter.dimensions(page));
            assertArrayEquals(new float[]{300, 200}, sizes.get(2), 0);
            LongImageExporter.Plan standard = LongImageExporter.plan(sizes, Arrays.asList(0, 2), 0);
            assertEquals(1000, standard.width); assertArrayEquals(new int[]{1500, 667}, standard.pageHeights); assertEquals(2167, standard.height);
            assertEquals(1600, LongImageExporter.plan(sizes, Arrays.asList(0, 2), 1).width);
            List<Integer> manyPages = new ArrayList<>(); for (int i = 0; i < 30; i++) manyPages.add(0);
            LongImageExporter.Plan limited = LongImageExporter.plan(sizes, manyPages, 1); assertTrue(limited.width >= 250); assertTrue(limited.height <= 16000);
            assertThrows(IllegalArgumentException.class, () -> LongImageExporter.plan(sizes, Collections.emptyList(), 0));
            assertThrows(IllegalArgumentException.class, () -> LongImageExporter.plan(Collections.singletonList(new float[]{1, 100000}), Collections.singletonList(0), 0));
        } finally { input.delete(); }
    }

    @Test public void jpegStackUsesChosenPageOrderAndReportsCompletedPages() throws Exception {
        File input = document(), output = File.createTempFile("long-image-result-", ".jpg", context.getCacheDir());
        try (PDDocument pdf = PDDocument.load(input)) {
            List<float[]> sizes = new ArrayList<>(); for (PDPage page : pdf.getPages()) sizes.add(LongImageExporter.dimensions(page));
            List<Integer> progress = new ArrayList<>();
            LongImageExporter.write(pdf, sizes, Arrays.asList(2, 0), 0, output, new LongImageExporter.Job(), (done, total) -> { assertEquals(2, total); progress.add(done); });
            assertEquals(Arrays.asList(1, 2), progress);
            Bitmap result = BitmapFactory.decodeFile(output.getPath());
            try { assertNotNull(result); assertEquals(1000, result.getWidth()); assertEquals(2167, result.getHeight()); int first = result.getPixel(500, 300), second = result.getPixel(500, 1300); assertTrue(Color.blue(first) > 240); assertTrue(Color.red(first) < 15); assertTrue(Color.red(second) > 240); assertTrue(Color.blue(second) < 15); }
            finally { if (result != null) result.recycle(); }
            assertTrue(input.isFile());
        } finally { input.delete(); output.delete(); }
    }

    @Test public void cancellationRemovesPartialOutputAndStopsRenderingFurtherPages() throws Exception {
        File input = document(), output = File.createTempFile("cancelled-long-", ".jpg", context.getCacheDir());
        try (PDDocument pdf = PDDocument.load(input)) {
            List<float[]> sizes = new ArrayList<>(); for (PDPage page : pdf.getPages()) sizes.add(LongImageExporter.dimensions(page));
            LongImageExporter.Job job = new LongImageExporter.Job(); List<Integer> progress = new ArrayList<>();
            assertThrows(CancellationException.class, () -> LongImageExporter.write(pdf, sizes, Arrays.asList(0, 1), 0, output, job, (done, total) -> { progress.add(done); job.cancelled = true; }));
            assertEquals(Collections.singletonList(1), progress); assertFalse(output.exists());
            assertThrows(CancellationException.class, job::check);
        } finally { input.delete(); output.delete(); }
    }

    @Test public void sessionReusesExportsSavesExactBytesAndRecyclesPreviewsOnQualityChange() throws Exception {
        File input = document(), destination = File.createTempFile("long-image-copy-", ".jpg", context.getCacheDir());
        LongImageActivity.LongSession session = new LongImageActivity.LongSession((Application)context.getApplicationContext());
        try {
            main(() -> session.initialize(new Intent().putExtra("path", input.getPath()), null));
            UiTestWait.until(() -> !session.busy && session.dimensions.size() == 3, "Long-image session did not open the PDF");
            main(() -> session.requestPreview(0)); UiTestWait.until(() -> session.previews.containsKey(0), "Long-image preview did not render");
            Bitmap preview = session.previews.get(0);
            main(() -> { session.setQuality(1); assertTrue(preview.isRecycled()); session.setPages(new HashSet<>(Arrays.asList(2, 0))); session.export(LongImageActivity.LongSession.SAVE); });
            UiTestWait.until(() -> !session.busy && session.output != null, "Long-image export did not complete");
            File exported = session.output; byte[] expected = Files.readAllBytes(exported.toPath());
            main(() -> { assertEquals(Arrays.asList(0, 2), session.selected); session.pendingAction = 0; session.export(LongImageActivity.LongSession.SHARE); assertSame(exported, session.output); assertEquals(LongImageActivity.LongSession.SHARE, session.pendingAction); session.pendingAction = 0; session.deviceSaveSource = exported; session.saveToDevice(Uri.fromFile(destination)); });
            UiTestWait.until(() -> !session.busy && session.deviceSaveSource == null, "Saving long image did not complete");
            assertArrayEquals(expected, Files.readAllBytes(destination.toPath()));
            main(() -> session.export(LongImageActivity.LongSession.REGISTER));
            UiTestWait.until(() -> !session.busy && session.registerPdf != null, "Long image was not converted to a library PDF");
            try (PDDocument registered = PDDocument.load(session.registerPdf)) { assertEquals(1, registered.getNumberOfPages()); assertTrue(registered.getPage(0).getResources().getXObjectNames().iterator().hasNext()); }
        } finally { main(session::onCleared); session.worker.awaitTermination(5, TimeUnit.SECONDS); assertTrue(session.output == null || !session.output.exists()); assertTrue(session.registerPdf == null || !session.registerPdf.exists()); input.delete(); destination.delete(); }
    }

    @Test public void previewSelectionAndClaritySurviveActivityRecreation() throws Exception {
        File input = document(); AtomicReference<LongImageActivity.LongSession> active = new AtomicReference<>();
        try (ActivityScenario<LongImageActivity> scenario = ActivityScenario.launch(new Intent(context, LongImageActivity.class).putExtra("path", input.getPath()).putExtra("name", "Long image fixture"))) {
            scenario.onActivity(a -> active.set(new androidx.lifecycle.ViewModelProvider(a).get(LongImageActivity.LongSession.class)));
            UiTestWait.until(() -> !active.get().busy && active.get().dimensions.size() == 3, "Long-image screen did not open");
            scenario.onActivity(a -> { active.get().setPages(Collections.singleton(2)); active.get().setQuality(1); });
            scenario.recreate(); scenario.onActivity(a -> { LongImageActivity.LongSession restored = new androidx.lifecycle.ViewModelProvider(a).get(LongImageActivity.LongSession.class); assertSame(active.get(), restored); assertEquals(Collections.singletonList(2), restored.selected); assertEquals(1, restored.quality); Bundle saved = new Bundle(); restored.saveState(saved); assertEquals(Collections.singletonList(2), saved.getIntegerArrayList("long_selected")); });
        } finally { input.delete(); }
    }
}
