package com.example.myapplication;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.media.ExifInterface;
import android.net.Uri;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

/** Persistence and pixel-level contracts for private, reusable signing marks. */
public class SignatureStoreTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

    private Bitmap ink() {
        Bitmap image = Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888);
        image.eraseColor(Color.WHITE);
        for (int y = 12; y < 16; y++) for (int x = 13; x < 21; x++) image.setPixel(x, y, Color.BLACK);
        return image;
    }

    @Test public void savedMarksPreservePixelsSeparateKindsAndSortNewestFirst() throws Exception {
        Bitmap image = ink(); ArrayList<File> created = new ArrayList<>();
        try {
            File older = SignatureStore.save(context, image, false); created.add(older);
            File newer = SignatureStore.save(context, image, false); created.add(newer);
            File stamp = SignatureStore.save(context, image, true); created.add(stamp);
            assertTrue(older.setLastModified(1000)); assertTrue(newer.setLastModified(2000));
            ArrayList<File> signatures = SignatureStore.list(context, false);
            assertTrue(signatures.indexOf(newer) < signatures.indexOf(older));
            assertFalse(signatures.contains(stamp));
            assertTrue(SignatureStore.list(context, true).contains(stamp));
            assertFalse(SignatureStore.list(context, true).contains(newer));
            Bitmap decoded = BitmapFactory.decodeFile(newer.getPath());
            try { assertNotNull(decoded); assertTrue(image.sameAs(decoded)); }
            finally { if (decoded != null) decoded.recycle(); }
        } finally { image.recycle(); for (File file : created) file.delete(); }
    }

    @Test public void backgroundRemovalCropsWithPaddingAndKeepsInkOpacity() throws Exception {
        Bitmap image = ink(), trimmed = null;
        try {
            trimmed = SignatureStore.trimBackground(image);
            assertEquals(20, trimmed.getWidth()); assertEquals(16, trimmed.getHeight());
            assertEquals(0, Color.alpha(trimmed.getPixel(0, 0)));
            assertEquals(Color.BLACK, trimmed.getPixel(6, 6));
            assertFalse(image.isRecycled());
        } finally { image.recycle(); if (trimmed != null) trimmed.recycle(); }
    }

    @Test public void blankAndTransparentImagesAreRejectedWithoutRecyclingTheCaller() throws Exception {
        for (int background : new int[]{Color.WHITE, Color.TRANSPARENT, 0xfff8f8f8}) {
            Bitmap image = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888); image.eraseColor(background);
            try { assertThrows(IllegalArgumentException.class, () -> SignatureStore.trimBackground(image)); assertFalse(image.isRecycled()); }
            finally { image.recycle(); }
        }
    }

    @Test public void edgeInkAndPartiallyTransparentInkKeepCorrectBounds() throws Exception {
        Bitmap image = Bitmap.createBitmap(3, 2, Bitmap.Config.ARGB_8888); image.eraseColor(Color.WHITE);
        image.setPixel(0, 0, 0x80000000); image.setPixel(2, 1, Color.BLACK);
        Bitmap trimmed = SignatureStore.trimBackground(image);
        try { assertEquals(3, trimmed.getWidth()); assertEquals(2, trimmed.getHeight()); assertEquals(128, Color.alpha(trimmed.getPixel(0, 0))); }
        finally { trimmed.recycle(); image.recycle(); }
    }

    @Test public void dateCleanupOnlyDeletesOwnedDirectChildren() throws Exception {
        Bitmap image = ink(); File date = null;
        File outside = File.createTempFile("date-", ".png", context.getCacheDir());
        File nestedDirectory = null, nested = null, unrelated = null;
        try {
            date = SignatureStore.saveTemporaryDate(context, image);
            nestedDirectory = new File(date.getParentFile(), "nested-" + java.util.UUID.randomUUID()); assertTrue(nestedDirectory.mkdir());
            nested = new File(nestedDirectory, "date-nested.png"); Files.write(nested.toPath(), new byte[]{1});
            unrelated = File.createTempFile("other-", ".png", date.getParentFile());
            assertTrue(SignatureStore.isOwnedTemporaryDate(context, date.getPath()));
            assertFalse(SignatureStore.isOwnedTemporaryDate(context, null));
            assertFalse(SignatureStore.isOwnedTemporaryDate(context, nested.getPath()));
            assertFalse(SignatureStore.isOwnedTemporaryDate(context, unrelated.getPath()));
            SignatureStore.removeTemporaryDates(context, Arrays.asList(null, date.getPath(), outside.getPath(), nested.getPath(), unrelated.getPath()));
            assertFalse(date.exists()); assertTrue(outside.exists()); assertTrue(nested.exists()); assertTrue(unrelated.exists());
        } finally { image.recycle(); if (date != null) date.delete(); outside.delete(); if (nested != null) nested.delete(); if (nestedDirectory != null) nestedDirectory.delete(); if (unrelated != null) unrelated.delete(); }
    }

    @Test public void captureCleanupRejectsExternalUrisTraversalAndSiblingDirectories() throws Exception {
        File captures = new File(context.getFilesDir(), "captures"); assertTrue(captures.isDirectory() || captures.mkdirs());
        File owned = File.createTempFile("signature-test-", ".jpg", captures);
        File ownedUri = File.createTempFile("signature-test-", ".jpg", captures);
        File outside = File.createTempFile("signature-test-", ".jpg", context.getFilesDir());
        File sibling = new File(context.getFilesDir(), "captures-sibling-" + java.util.UUID.randomUUID()); assertTrue(sibling.mkdir());
        File other = File.createTempFile("test-", ".jpg", sibling);
        try {
            SignatureStore.removeOwnedCaptures(context, null);
            SignatureStore.removeOwnedCaptures(context, Arrays.asList(null, owned.getPath(), Uri.fromFile(ownedUri).toString(), "content://fixture/" + outside.getName(), new File(captures, "../" + outside.getName()).getPath(), other.getPath(), "file://"));
            assertFalse(owned.exists()); assertFalse(ownedUri.exists()); assertTrue(outside.exists()); assertTrue(other.exists());
        } finally { owned.delete(); ownedUri.delete(); outside.delete(); other.delete(); sibling.delete(); }
    }

    @Test public void importRejectsMalformedAndBlankImages() throws Exception {
        File invalid = File.createTempFile("signature-test-", ".bin", context.getCacheDir());
        File blank = File.createTempFile("signature-test-", ".png", context.getCacheDir());
        Bitmap image = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888); image.eraseColor(Color.WHITE);
        try {
            Files.write(invalid.toPath(), new byte[]{1, 2, 3});
            try (FileOutputStream stream = new FileOutputStream(blank)) { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, stream)); }
            assertThrows(IllegalArgumentException.class, () -> SignatureStore.importMark(context, Uri.fromFile(invalid)));
            assertThrows(IllegalArgumentException.class, () -> SignatureStore.importMark(context, Uri.fromFile(blank)));
        } finally { image.recycle(); invalid.delete(); blank.delete(); }
    }

    @Test public void importAppliesEveryExifOrientationBeforeCropping() throws Exception {
        for (int orientation = 1; orientation <= 8; orientation++) {
            File source = File.createTempFile("signature-exif-", ".jpg", context.getCacheDir()); Bitmap image = ink(), decoded = null;
            try {
                try (FileOutputStream stream = new FileOutputStream(source)) { assertTrue(image.compress(Bitmap.CompressFormat.JPEG, 100, stream)); }
                ExifInterface exif = new ExifInterface(source.getPath()); exif.setAttribute(ExifInterface.TAG_ORIENTATION, Integer.toString(orientation)); exif.saveAttributes();
                decoded = SignatureStore.importMark(context, Uri.fromFile(source));
                boolean transposed = orientation >= 5;
                assertEquals("Orientation " + orientation, transposed ? 16 : 20, decoded.getWidth());
                assertEquals("Orientation " + orientation, transposed ? 20 : 16, decoded.getHeight());
                assertTrue(source.isFile());
            } finally { image.recycle(); if (decoded != null) decoded.recycle(); source.delete(); }
        }
    }
}
