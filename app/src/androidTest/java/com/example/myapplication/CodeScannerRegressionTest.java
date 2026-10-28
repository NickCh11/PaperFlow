package com.example.myapplication;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.zxing.*;
import com.google.zxing.common.BitMatrix;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import static org.junit.Assert.*;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.*;

public class CodeScannerRegressionTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private void main(Runnable action) { InstrumentationRegistry.getInstrumentation().runOnMainSync(action); }
    private File qr(String text) throws Exception {
        BitMatrix matrix = new MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, 384, 384); Bitmap image = Bitmap.createBitmap(384, 384, Bitmap.Config.ARGB_8888);
        for (int y = 0; y < 384; y++) for (int x = 0; x < 384; x++) image.setPixel(x, y, matrix.get(x, y) ? Color.BLACK : Color.WHITE);
        File input = File.createTempFile("qr-fixture-", ".png", context.getCacheDir());
        try (FileOutputStream output = new FileOutputStream(input)) { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, output)); } finally { image.recycle(); }
        return input;
    }
    private ScanCodeActivity.ScanSession session() { return new ScanCodeActivity.ScanSession((Application)context.getApplicationContext()); }
    private void close(ScanCodeActivity.ScanSession session) throws Exception { main(session::onCleared); assertTrue(session.worker.awaitTermination(5, TimeUnit.SECONDS)); }

    @Test public void decodedCodesOnlyOfferBrowserActionsForHttpAndHttps() {
        for (String text : new String[]{"https://example.invalid/document", "http://example.invalid", "mailto:test@example.invalid", "javascript:alert(1)", "file:///private/report.pdf", "plain text"}) {
            ScanCodeActivity.CodeResult result = ScanCodeActivity.CodeResult.from(new Result(text, null, null, BarcodeFormat.QR_CODE));
            assertEquals(text, result.value); assertEquals("QR Code", result.format);
            if (text.startsWith("http")) assertEquals(text, result.openUri); else assertNull(result.openUri);
        }
        assertNull(ScanCodeActivity.CodeResult.from(new Result("", null, null, BarcodeFormat.QR_CODE)));
        assertEquals("CODE 128", ScanCodeActivity.CodeResult.from(new Result("12345", null, null, BarcodeFormat.CODE_128)).format);
    }

    @Test public void importedQrIsDisplayedAndRetainedAcrossRecreationWithoutOpeningItsUrl() throws Exception {
        String value = "https://example.invalid/local-qr-fixture"; File input = qr(value); AtomicReference<ScanCodeActivity.ScanSession> active = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().getUiAutomation().grantRuntimePermission(context.getPackageName(), android.Manifest.permission.CAMERA);
        try (ActivityScenario<ScanCodeActivity> scenario = ActivityScenario.launch(new Intent(context, ScanCodeActivity.class))) {
            scenario.onActivity(a -> { active.set(new androidx.lifecycle.ViewModelProvider(a).get(ScanCodeActivity.ScanSession.class)); active.get().importImage(Uri.fromFile(input)); });
            UiTestWait.until(() -> !active.get().importing && active.get().results.size() == 1, "Imported QR did not decode");
            onView(withText(value)).check(matches(isDisplayed()));
            scenario.recreate(); scenario.onActivity(a -> { assertSame(active.get(), new androidx.lifecycle.ViewModelProvider(a).get(ScanCodeActivity.ScanSession.class)); assertEquals(value, active.get().results.get(0).value); assertTrue(active.get().paused); });
            onView(withText(value)).check(matches(isDisplayed()));
        } finally { input.delete(); }
    }

    @Test public void failedImportsKeepTheLastResultAndRestartAllowsScanningAgain() throws Exception {
        ScanCodeActivity.ScanSession session = session(); File good = qr("retained-result"), invalid = File.createTempFile("qr-invalid-", ".bin", context.getCacheDir()), blank = File.createTempFile("qr-blank-", ".png", context.getCacheDir());
        try {
            Files.write(invalid.toPath(), new byte[]{1, 2, 3}); Bitmap image = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888); image.eraseColor(Color.WHITE); try (FileOutputStream stream = new FileOutputStream(blank)) { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, stream)); } finally { image.recycle(); }
            main(() -> session.importImage(Uri.fromFile(good))); UiTestWait.until(() -> !session.importing, "QR import did not finish"); assertEquals("retained-result", session.results.get(0).value);
            for (File failure : new File[]{invalid, blank}) {
                main(() -> session.importImage(Uri.fromFile(failure))); UiTestWait.until(() -> !session.importing, "Failed QR import did not finish");
                main(() -> { assertEquals("retained-result", session.results.get(0).value); assertTrue(session.paused); assertFalse(session.message.isEmpty()); });
            }
            main(() -> { Bundle saved = new Bundle(); session.save(saved); ScanCodeActivity.ScanSession restored = session(); try { restored.restore(saved); assertEquals("retained-result", restored.results.get(0).value); restored.resumeAfterPicker(); assertTrue(restored.paused); restored.restart(); assertTrue(restored.results.isEmpty()); assertFalse(restored.paused); assertNull(restored.candidate); } finally { restored.onCleared(); } });
        } finally { close(session); good.delete(); invalid.delete(); blank.delete(); }
    }

    @Test public void aNewImportSupersedesTheOlderQueuedImage() throws Exception {
        ScanCodeActivity.ScanSession session = session(); File first = qr("first-result"), latest = qr("latest-result"); CountDownLatch release = new CountDownLatch(1);
        session.worker.submit(() -> { try { assertTrue(release.await(10, TimeUnit.SECONDS)); } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); } });
        try {
            main(() -> { session.importImage(Uri.fromFile(first)); session.importImage(Uri.fromFile(latest)); }); release.countDown();
            UiTestWait.until(() -> !session.importing && !session.processing.get() && session.pendingImport == null, "Latest queued QR import did not finish");
            main(() -> { assertEquals(1, session.results.size()); assertEquals("latest-result", session.results.get(0).value); });
        } finally { release.countDown(); close(session); first.delete(); latest.delete(); }
    }
}
