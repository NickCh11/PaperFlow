package com.example.myapplication;

import com.paperflow.imageprocessing.ColorMode;
import com.paperflow.imageprocessing.PostProcessingKt;
import com.paperflow.imageprocessing.UtilsKt;
import com.paperflow.imageprocessing.DocumentDetectionKt;
import com.paperflow.imageprocessing.ImageSize;
import com.paperflow.imageprocessing.Mask;
import com.paperflow.imageprocessing.Mode;
import com.paperflow.imageprocessing.Quad;
import org.junit.Before;
import org.junit.Test;
import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;
import static org.junit.Assert.*;

/** Uses the shipped OpenCV library and checks the resulting image, not mocked JNI calls. */
public class ImageProcessingRegressionTest {
    @Before public void loadNativeLibrary() { assertTrue(com.paperflow.scanengine.ScanEngine.ready()); }

    private Mat page() {
        Mat image = new Mat(200, 300, CvType.CV_8UC3);
        byte[] row = new byte[300 * 3];
        for (int y = 0; y < 200; y++) {
            for (int x = 0; x < 300; x++) { byte shade = (byte)(190 + x * 55 / 300); for (int channel = 0; channel < 3; channel++) row[x * 3 + channel] = shade; }
            image.put(y, 0, row);
        }
        Imgproc.rectangle(image, new Point(40, 40), new Point(80, 160), new Scalar(20, 20, 20), -1);
        Imgproc.rectangle(image, new Point(120, 80), new Point(230, 105), new Scalar(35, 35, 35), -1);
        return image;
    }

    @Test public void enhancementModesPreserveSourceAndReadableDarkContent() {
        Mat image = page(), original = image.clone();
        try {
            for (ColorMode mode : ColorMode.values()) {
                Mat enhanced = PostProcessingKt.enhanceCapturedImage(image, mode, 0L);
                try {
                    assertEquals(300, enhanced.cols()); assertEquals(200, enhanced.rows());
                    assertEquals(CvType.CV_8U, enhanced.depth());
                    assertEquals(mode == ColorMode.BLACK_AND_WHITE ? 1 : 3, enhanced.channels());
                    assertTrue("Dark content was lost in " + mode, enhanced.get(70, 60)[0] < enhanced.get(20, 270)[0]);
                    assertEquals(0, Core.norm(image, original, Core.NORM_INF), 0);
                } finally { enhanced.release(); }
            }
        } finally { image.release(); original.release(); }
    }

    @Test public void grayscaleAcceptsSingleBgrAndBgraChannels() {
        Mat image = page(), gray = new Mat(), bgra = new Mat();
        Imgproc.cvtColor(image, gray, Imgproc.COLOR_BGR2GRAY); Imgproc.cvtColor(image, bgra, Imgproc.COLOR_BGR2BGRA);
        try {
            for (Mat source : new Mat[]{gray, image, bgra}) {
                Mat result = PostProcessingKt.enhanceGrayscaleImage(source);
                try {
                    assertEquals(3, result.channels()); assertEquals(image.size(), result.size());
                    double[] pixel = result.get(70, 60); assertEquals(pixel[0], pixel[1], 0); assertEquals(pixel[1], pixel[2], 0);
                    assertTrue(pixel[0] < result.get(20, 270)[0]);
                } finally { result.release(); }
            }
        } finally { image.release(); gray.release(); bgra.release(); }
    }

    @Test public void bitonalUpscalingPreservesAspectAndProducesOnlyBlackOrWhite() {
        Mat image = page(); Mat binary = PostProcessingKt.binarizeDocument(image, 240000L);
        try {
            assertEquals(600, binary.cols()); assertEquals(400, binary.rows()); assertEquals(1, binary.channels());
            byte[] pixels = new byte[(int)binary.total()]; binary.get(0, 0, pixels);
            int black = 0, white = 0;
            for (byte pixel : pixels) { assertTrue(pixel == 0 || pixel == (byte)255); if (pixel == 0) black++; else white++; }
            assertTrue(black > 1000); assertTrue(white > 1000);
            assertEquals(0, binary.get(140, 120)[0], 0);
        } finally { image.release(); binary.release(); }
    }

    @Test public void constantImagesRemainFiniteAcrossEnhancementPipelines() {
        for (int intensity : new int[]{0, 255}) {
            Mat image = new Mat(100, 100, CvType.CV_8UC3, new Scalar(intensity, intensity, intensity));
            try {
                for (ColorMode mode : ColorMode.values()) {
                    Mat result = PostProcessingKt.enhanceCapturedImage(image, mode, 0L);
                    try { assertFalse(result.empty()); assertTrue(Core.checkRange(result)); assertEquals(image.size(), result.size()); }
                    finally { result.release(); }
                }
            } finally { image.release(); }
        }
    }

    @Test public void percentileUsesTheImageHistogramAndHandlesUpperTail() {
        Mat values = new Mat(1, 4, CvType.CV_8UC1); values.put(0, 0, new byte[]{10, 20, 30, (byte)240});
        try {
            assertEquals(10, PostProcessingKt.percentileL(values, .25), 0);
            assertEquals(20, PostProcessingKt.percentileL(values, .5), 0);
            assertEquals(240, PostProcessingKt.percentileL(values, 1), 0);
        } finally { values.release(); }
    }

    @Test public void codecsRoundTripPixelsAndResizingDoesNotEnlargeSmallInputs() {
        Mat image = page(), decoded = null, jpeg = null, small = null, clone = null;
        try {
            decoded = UtilsKt.decodeJpegOrPng(UtilsKt.encodePng(image)); assertEquals(0, Core.norm(image, decoded, Core.NORM_INF), 0);
            jpeg = UtilsKt.decodeJpegOrPng(UtilsKt.encodeJpeg(image, 120)); assertEquals(image.size(), jpeg.size());
            assertTrue(Core.norm(image, jpeg, Core.NORM_L1) / (image.total() * 3) < 3);
            small = UtilsKt.resizeForMaxPixels(image, 15000, Imgproc.INTER_AREA); assertEquals(150, small.cols()); assertEquals(100, small.rows());
            clone = UtilsKt.resizeForMaxPixels(image, 100000, Imgproc.INTER_AREA); assertNotEquals(image.dataAddr(), clone.dataAddr()); assertEquals(image.size(), clone.size());
            assertThrows(IllegalStateException.class, () -> UtilsKt.decodeJpegOrPng(new byte[]{1, 2, 3}));
        } finally { image.release(); for (Mat mat : new Mat[]{decoded, jpeg, small, clone}) if (mat != null) mat.release(); }
    }

    @Test public void documentMasksDetectPageCornersAndReleaseTheirNativeCopy() {
        Mat probability = new Mat(200, 300, CvType.CV_32FC1, new Scalar(0));
        Imgproc.rectangle(probability, new Point(40, 30), new Point(260, 170), new Scalar(.99), -1);
        Mat[] issued = new Mat[1];
        Mask mask = new Mask() {
            @Override public int getWidth() { return 300; }
            @Override public int getHeight() { return 200; }
            @Override public Mat toMat() { issued[0] = probability.clone(); return issued[0]; }
        };
        try {
            for (Mode mode : Mode.values()) {
                Quad detected = DocumentDetectionKt.detectDocumentQuad(mask, new ImageSize(600, 400), mode);
                assertNotNull("Document missing in " + mode, detected); assertTrue(detected.isConvex());
                assertEquals(40, detected.getTopLeft().getX(), 8); assertEquals(30, detected.getTopLeft().getY(), 8);
                assertEquals(260, detected.getBottomRight().getX(), 8); assertEquals(170, detected.getBottomRight().getY(), 8);
                assertTrue(issued[0].empty());
            }
            probability.setTo(new Scalar(0));
            for (Mode mode : Mode.values()) { assertNull(DocumentDetectionKt.detectDocumentQuad(mask, new ImageSize(600, 400), mode)); assertTrue(issued[0].empty()); }
        } finally { probability.release(); }
    }

    @Test public void extractedDocumentHasExpectedCropAndRotationWithoutMutatingInput() {
        Mat image = page(), original = image.clone();
        Quad crop = new Quad(new com.paperflow.imageprocessing.Point(30, 30), new com.paperflow.imageprocessing.Point(270, 30), new com.paperflow.imageprocessing.Point(270, 170), new com.paperflow.imageprocessing.Point(30, 170));
        try {
            for (int angle : new int[]{0, 90, 180, 270}) {
                Mat extracted = DocumentDetectionKt.extractDocument(image, crop, angle, ColorMode.GRAYSCALE, 100000L, null);
                // The continuous perspective size is rounded to integer raster pixels by OpenCV.
                try { assertEquals(angle % 180 == 0 ? 240 : 140, extracted.cols(), 1); assertEquals(angle % 180 == 0 ? 140 : 240, extracted.rows(), 1); assertEquals(3, extracted.channels()); assertEquals(0, Core.norm(image, original, Core.NORM_INF), 0); }
                finally { extracted.release(); }
            }
        } finally { image.release(); original.release(); }
    }

    @Test public void nativeRotationUsesIndependentBuffersAndNormalizesNegativeAngles() {
        Mat image = new Mat(2, 3, CvType.CV_8UC1); image.put(0, 0, new byte[]{1, 2, 3, 4, 5, 6});
        try {
            for (int angle : new int[]{0, 90, 180, 270, -90, 450}) {
                Mat rotated = DocumentDetectionKt.rotate(image, angle), restored = DocumentDetectionKt.rotate(rotated, -angle);
                try { assertNotEquals(image.dataAddr(), rotated.dataAddr()); assertEquals(0, Core.norm(image, restored, Core.NORM_INF), 0); }
                finally { rotated.release(); restored.release(); }
            }
            assertThrows(IllegalArgumentException.class, () -> DocumentDetectionKt.rotate(image, 45));
        } finally { image.release(); }
    }
}
