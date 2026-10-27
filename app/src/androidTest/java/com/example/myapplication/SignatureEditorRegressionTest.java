package com.example.myapplication;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.*;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.pdmodel.encryption.*;
import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import static org.junit.Assert.*;

public class SignatureEditorRegressionTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

    private android.widget.Button bulkApplyButton() {
        for (android.view.View root : android.view.inspector.WindowInspector.getGlobalWindowViews()) {
            android.widget.Button button = root.findViewById(android.R.id.button1);
            if (button != null && button.isShown() && button.isEnabled() && UiText.get(R.string.ui_apply).contentEquals(button.getText())) return button;
        }
        return null;
    }

    private SignatureEditorActivity.Session state(SignatureEditorActivity activity) {
        try { java.lang.reflect.Field field = SignatureEditorActivity.class.getDeclaredField("session"); field.setAccessible(true); return (SignatureEditorActivity.Session)field.get(activity); }
        catch (Exception error) { throw new AssertionError(error); }
    }
    private void invoke(SignatureEditorActivity activity, String name, Class<?>[] types, Object... args) {
        try { java.lang.reflect.Method method = SignatureEditorActivity.class.getDeclaredMethod(name, types); method.setAccessible(true); method.invoke(activity, args); }
        catch (Exception error) { throw new AssertionError(error); }
    }
    private File document(boolean encrypted) throws Exception {
        PDFBoxResourceLoader.init(context); File input = File.createTempFile("signature-session-", ".pdf", context.getCacheDir());
        try (PDDocument doc = new PDDocument()) {
            for (int rotation : new int[]{0, 90, 180, 270}) { PDPage page = new PDPage(new PDRectangle(200, 300)); page.setRotation(rotation); doc.addPage(page); }
            if (encrypted) { StandardProtectionPolicy policy = new StandardProtectionPolicy("fixture-owner", "fixture-user", new AccessPermission()); policy.setEncryptionKeyLength(128); policy.setPreferAES(true); doc.protect(policy); }
            doc.save(input);
        }
        return input;
    }
    private ActivityScenario<SignatureEditorActivity> open(File file, boolean encrypted, AtomicReference<SignatureEditorActivity.Session> active) {
        ActivityScenario<SignatureEditorActivity> scenario = ActivityScenario.launch(new Intent(context, SignatureEditorActivity.class).putExtra("path", file.getPath()).putExtra("name", "Signature fixture").putExtra("pdf_password", encrypted ? "fixture-user" : ""));
        scenario.onActivity(a -> active.set(state(a)));
        UiTestWait.until(() -> !active.get().busy && active.get().pageBitmap != null, "Signing preview did not finish rendering");
        return scenario;
    }

    @Test public void datesSurviveRecreationAndAreRemovedWhenTheSessionCloses() throws Exception {
        File input = document(false); AtomicReference<SignatureEditorActivity.Session> active = new AtomicReference<>(); AtomicReference<File> date = new AtomicReference<>();
        try {
            try (ActivityScenario<SignatureEditorActivity> scenario = open(input, false, active)) {
                scenario.onActivity(a -> {
                    invoke(a, "addDate", new Class<?>[]{String.class}, "2026-10-24");
                    assertEquals(1, active.get().marks.size()); date.set(new File(active.get().marks.get(0).file)); assertTrue(date.get().isFile());
                    invoke(a, "changePage", new Class<?>[]{int.class}, 1);
                });
                UiTestWait.until(() -> !active.get().busy && active.get().page == 1, "Next signing page did not render");
                scenario.recreate(); scenario.onActivity(a -> { assertSame(active.get(), state(a)); assertEquals(1, state(a).page); assertEquals(0, state(a).marks.get(0).page); });
            }
            active.get().worker.awaitTermination(5, TimeUnit.SECONDS);
            assertFalse(date.get().exists()); assertTrue(input.isFile());
        } finally { input.delete(); if (date.get() != null) date.get().delete(); }
    }

    @Test public void bulkCopiesShareTheImageUntilTheLastMarkIsRemoved() throws Exception {
        File input = document(false); AtomicReference<SignatureEditorActivity.Session> active = new AtomicReference<>(); AtomicReference<Bitmap> image = new AtomicReference<>(); AtomicReference<File> markFile = new AtomicReference<>();
        try (ActivityScenario<SignatureEditorActivity> scenario = open(input, false, active)) {
            scenario.onActivity(a -> {
                invoke(a, "addDate", new Class<?>[]{String.class}, "2026-10-24"); SignatureEditorActivity.Mark original = active.get().marks.get(0);
                image.set(original.bitmap); markFile.set(new File(original.file)); original.angle = 90;
                invoke(a, "bulkMark", new Class<?>[]{SignatureEditorActivity.Mark.class, boolean.class}, original, true);
            });
            UiTestWait.until(() -> bulkApplyButton() != null, "Bulk signing Apply button did not appear");
            scenario.onActivity(a -> { android.widget.Button button = bulkApplyButton(); assertNotNull(button); assertTrue(button.performClick()); });
            scenario.onActivity(a -> {
                assertEquals(4, active.get().marks.size());
                for (int page = 0; page < 4; page++) { SignatureEditorActivity.Mark mark = active.get().marks.get(page); assertEquals(page, mark.page); assertEquals(90, mark.angle); assertSame(image.get(), mark.bitmap); }
                for (int remaining = 3; remaining >= 0; remaining--) {
                    active.get().selected = 0; invoke(a, "removeSelected", new Class<?>[]{});
                    assertEquals(remaining, active.get().marks.size()); assertEquals(remaining == 0, image.get().isRecycled());
                }
                assertTrue(markFile.get().isFile());
            });
        } finally { input.delete(); if (markFile.get() != null) markFile.get().delete(); }
    }

    @Test public void signingEveryPageRotationPreservesOriginalAndPdfEncryption() throws Exception {
        for (boolean encrypted : new boolean[]{false, true}) {
            File input = document(encrypted); byte[] original = Files.readAllBytes(input.toPath()); AtomicReference<SignatureEditorActivity.Session> active = new AtomicReference<>(); File output = null;
            try (ActivityScenario<SignatureEditorActivity> scenario = open(input, encrypted, active)) {
                scenario.onActivity(a -> {
                    // Keep the completed artifact in this test session instead of opening the library.
                    active.get().changed = null;
                    for (int page = 0; page < 4; page++) {
                        Bitmap ink = Bitmap.createBitmap(30, 10, Bitmap.Config.ARGB_8888); ink.eraseColor(Color.BLACK);
                        SignatureEditorActivity.Mark mark = new SignatureEditorActivity.Mark("in-memory-fixture", page, ink);
                        mark.x = -1; mark.y = 2; mark.width = .4f; mark.angle = page * 90; active.get().marks.add(mark);
                    }
                    invoke(a, "save", new Class<?>[]{});
                });
                active.get().worker.submit(() -> {}).get(15, TimeUnit.SECONDS);
                assertNull(active.get().error); assertNotNull(active.get().result); output = new File(active.get().result); assertTrue(output.isFile());
                try (PDDocument signed = PDDocument.load(output, encrypted ? "fixture-user" : "")) {
                    assertEquals(4, signed.getNumberOfPages()); assertEquals(encrypted, signed.isEncrypted());
                    for (int page = 0; page < 4; page++) {
                        PDPage saved = signed.getPage(page); assertEquals(page * 90, saved.getRotation());
                        assertTrue(saved.getResources().getXObjectNames().iterator().hasNext());
                        Bitmap rendered = new com.tom_roush.pdfbox.rendering.PDFRenderer(signed).renderImage(page, 1f);
                        try { int dark = 0; for (int y = 0; y < rendered.getHeight(); y++) for (int x = 0; x < rendered.getWidth(); x++) if (Color.red(rendered.getPixel(x, y)) < 64) dark++; assertTrue("Signed mark must be visible inside page " + page, dark > 100); }
                        finally { rendered.recycle(); }
                    }
                }
                if (encrypted) { File artifact = output; assertThrows(InvalidPasswordException.class, () -> { try (PDDocument ignored = PDDocument.load(artifact)) { fail("Encrypted signed copy opened without a password"); } }); }
                assertArrayEquals(original, Files.readAllBytes(input.toPath()));
            } finally { input.delete(); if (output != null) output.delete(); }
        }
    }
}
