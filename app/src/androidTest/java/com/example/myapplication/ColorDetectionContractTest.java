package com.example.myapplication;

import com.paperflow.imageprocessing.ColorDetectionKt;
import com.paperflow.imageprocessing.ColorMode;
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

/** Real OpenCV contracts for borrowed masks, white balance and document fallback. */
public class ColorDetectionContractTest {
    @Before public void loadNativeLibrary() { assertTrue(com.paperflow.scanengine.ScanEngine.ready()); }

    private Quad full(int width,int height) {
        return new Quad(new com.paperflow.imageprocessing.Point(0,0),new com.paperflow.imageprocessing.Point(width,0),
                new com.paperflow.imageprocessing.Point(width,height),new com.paperflow.imageprocessing.Point(0,height));
    }
    private Mask mask(Mat source) {
        return new Mask() {
            @Override public int getWidth() { return source.cols(); }
            @Override public int getHeight() { return source.rows(); }
            @Override public Mat toMat() { return source.clone(); }
        };
    }
    @Test public void emptyWhiteBalanceMaskRemainsOwnedByItsCaller() {
        Mat image=new Mat(30,40,CvType.CV_8UC3,new Scalar(30,90,150));
        Mat selected=new Mat(30,40,CvType.CV_8UC1,new Scalar(0)),result=null;
        try {
            result=ColorDetectionKt.applyGrayWorldToDocument(image,selected);
            assertFalse("White balance released the caller's mask",selected.empty());
            assertEquals(image.size(),selected.size());assertEquals(0,Core.countNonZero(selected));
            assertNotEquals(image.dataAddr(),result.dataAddr());assertEquals(0,Core.norm(image,result,Core.NORM_INF),0);
            selected.setTo(new Scalar(255));assertEquals(1200,Core.countNonZero(selected));
        } finally { image.release();selected.release();if(result!=null)result.release(); }
    }
    @Test public void whiteBalanceOnlyChangesSelectedPixelsAndHandlesMissingColorChannels() {
        for(Scalar color:new Scalar[]{new Scalar(30,90,150),new Scalar(0,90,150),new Scalar(30,0,150),new Scalar(30,90,0),new Scalar(0,0,0)}) {
            Mat image=new Mat(30,40,CvType.CV_8UC3,color),selected=new Mat(30,40,CvType.CV_8UC1,new Scalar(0));
            Mat original=image.clone(),result=null;
            Imgproc.rectangle(selected,new Point(10,5),new Point(29,24),new Scalar(255),-1);
            try {
                result=ColorDetectionKt.applyGrayWorldToDocument(image,selected);
                assertEquals(0,Core.norm(image,original,Core.NORM_INF),0);assertEquals(400,Core.countNonZero(selected));
                assertArrayEquals(image.get(0,0),result.get(0,0),0);assertTrue(Core.checkRange(result));
                double target=(color.val[0]+color.val[1]+color.val[2])/3.0;
                for(int c=0;c<3;c++)assertEquals(color.val[c]==0?0:target,result.get(10,15)[c],1);
            } finally { image.release();selected.release();original.release();if(result!=null)result.release(); }
        }
        Mat gray=new Mat(8,8,CvType.CV_8UC1,new Scalar(40)),selected=new Mat(8,8,CvType.CV_8UC1,new Scalar(255));
        try { assertThrows(IllegalArgumentException.class,()->ColorDetectionKt.applyGrayWorldToDocument(gray,selected));assertFalse(selected.empty()); }
        finally { gray.release();selected.release(); }
    }
    @Test public void automaticColorIgnoresPixelsOutsideTheDocumentAndAnEmptyMask() {
        Mat image=new Mat(100,120,CvType.CV_8UC3,new Scalar(100,100,100));
        Mat selected=new Mat(100,120,CvType.CV_8UC1,new Scalar(255));
        Quad page=full(120,100);
        try {
            assertEquals(ColorMode.GRAYSCALE,ColorDetectionKt.autoColorMode(image,mask(selected),page,17.5,.0003,40,180));
            Imgproc.rectangle(image,new Point(30,30),new Point(80,70),new Scalar(30,60,180),-1);
            assertEquals(ColorMode.COLOR,ColorDetectionKt.autoColorMode(image,mask(selected),page,17.5,.0003,40,180));
            selected.setTo(new Scalar(0));
            assertEquals(ColorMode.GRAYSCALE,ColorDetectionKt.autoColorMode(image,mask(selected),page,17.5,.0003,40,180));
            assertFalse(selected.empty());
            selected.setTo(new Scalar(255));Imgproc.rectangle(selected,new Point(20,20),new Point(90,80),new Scalar(0),-1);
            assertEquals(ColorMode.GRAYSCALE,ColorDetectionKt.autoColorMode(image,mask(selected),page,17.5,.0003,40,180));
        } finally { image.release();selected.release(); }
    }
    @Test public void captureUsesBoundingRectangleWhenThreeSidedMaskCannotDefineFourEdges() {
        Mat probability=new Mat(200,300,CvType.CV_32FC1,new Scalar(0));
        MatOfPoint triangle=new MatOfPoint(new Point(100,130),new Point(150,65),new Point(200,130));
        Imgproc.fillConvexPoly(probability,triangle,new Scalar(1));
        try {
            assertNull(DocumentDetectionKt.detectDocumentQuad(mask(probability),new ImageSize(600,400),Mode.IMPORT));
            Quad fallback=DocumentDetectionKt.detectDocumentQuad(mask(probability),new ImageSize(600,400),Mode.CAPTURE);
            assertNotNull("Capture lost a three-sided document candidate",fallback);assertTrue(fallback.isConvex());
            for(com.paperflow.imageprocessing.Point point:new com.paperflow.imageprocessing.Point[]{fallback.getTopLeft(),fallback.getTopRight(),fallback.getBottomRight(),fallback.getBottomLeft()})
                assertTrue(DocumentDetectionKt.isInsideImage(point,new ImageSize(300,200)));
        } finally { probability.release();triangle.release(); }
    }
}
