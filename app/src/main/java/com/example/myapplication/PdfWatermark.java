package com.example.myapplication;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;

import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory;
import com.tom_roush.pdfbox.util.Matrix;

/** The same displayed-page coordinates drive the preview and PDF overlay. */
final class PdfWatermark {
    private PdfWatermark() {}
    static void remove(PDPage page)throws java.io.IOException{java.util.List<com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation> kept=new java.util.ArrayList<>();for(com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation mark:page.getAnnotations())if(mark.getCOSObject().getString("PFWatermark")==null)kept.add(mark);page.setAnnotations(kept);}
    static org.json.JSONObject settings(PDPage page)throws Exception{for(com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation mark:page.getAnnotations()){String value=mark.getCOSObject().getString("PFWatermark");if(value!=null)return new org.json.JSONObject(value);}return null;}

    static void draw(Canvas canvas, float width, float height, String text, int color,
                     int opacity, int size, int angle, boolean tile, int spacing) {
        if (text.trim().isEmpty() || opacity == 0 || width <= 0 || height <= 0) return;
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.argb(opacity * 255 / 100, Color.red(color), Color.green(color), Color.blue(color)));
        paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        float unit = width * (80f / 1200f) * (.2f + 4.8f * size / 100f);
        paint.setTextSize(unit);
        // Long text fits the page in both modes; dimensions stay proportional at every resolution.
        float measured = paint.measureText(text);
        if (measured > width * .80f) paint.setTextSize(paint.getTextSize() * width * .80f / measured);
        float textWidth = paint.measureText(text), baseline = -(paint.ascent() + paint.descent()) / 2;
        canvas.save();
        canvas.clipRect(0, 0, width, height);
        canvas.translate(width / 2, height / 2);
        canvas.rotate(angle);
        if (!tile) canvas.drawText(text, -textWidth / 2, baseline, paint);
        else {
            // Cover the rotated page, including corners. The positive minimum steps bound the work.
            float stepX = Math.max(width * .08f, textWidth + unit * (.75f + spacing * .055f));
            float stepY = Math.max(width * .06f, unit * (2f + spacing * .055f));
            float radius = (float) Math.hypot(width, height) / 2;
            int rows = (int) Math.ceil(radius / stepY) + 1;
            int columns = (int) Math.ceil(radius / stepX) + 1;
            for (int row = -rows; row <= rows; row++) {
                float stagger = (row & 1) == 0 ? 0 : stepX / 2;
                for (int column = -columns; column <= columns; column++)
                    canvas.drawText(text, column * stepX + stagger - textWidth / 2,
                            row * stepY + baseline, paint);
            }
        }
        canvas.restore();
    }

    static void append(PDDocument document, int index, String text, int color, int opacity,
                       int size, int angle, boolean tile, int spacing) throws Exception {
        if (opacity == 0 || text.trim().isEmpty()) return;
        PDPage page = document.getPage(index);
        PDRectangle box = page.getCropBox();
        int rotation = ((page.getRotation() % 360) + 360) % 360;
        boolean turn = rotation == 90 || rotation == 270;
        float width = turn ? box.getHeight() : box.getWidth();
        float height = turn ? box.getWidth() : box.getHeight();
        if (width <= 0 || height <= 0) throw new IllegalArgumentException(UiText.get(R.string.ui_invalid_page_dimensions));
        float scale = Math.min(1400f / width, 2400f / height);
        int bitmapWidth = Math.max(1, Math.round(width * scale));
        int bitmapHeight = Math.max(1, Math.round(height * scale));
        Bitmap bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas = new Canvas(bitmap);
            // Use the PDF aspect ratio exactly, avoiding pixel-rounding drift between preview/export.
            canvas.scale(bitmapWidth / width, bitmapHeight / height);
            draw(canvas, width, height, text, color, opacity, size, angle, tile, spacing);
            float x = 0, y = 0;
            Matrix matrix;
            switch (rotation) {
                case 90: matrix = new Matrix(0, width, -height, 0, x + box.getWidth(), y); break;
                case 180: matrix = new Matrix(-width, 0, 0, -height, x + box.getWidth(), y + box.getHeight()); break;
                case 270: matrix = new Matrix(0, -width, height, 0, x, y + box.getHeight()); break;
                default: matrix = new Matrix(width, 0, 0, height, x, y);
            }
            com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationRubberStamp mark=new com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationRubberStamp();
            mark.setRectangle(new PDRectangle(box.getLowerLeftX(),box.getLowerLeftY(),box.getWidth(),box.getHeight()));mark.setPrinted(true);mark.setName("PaperFlowWatermark");
            org.json.JSONObject settings=new org.json.JSONObject().put("text",text).put("color",color).put("opacity",opacity).put("size",size).put("angle",angle).put("tile",tile).put("spacing",spacing);
            mark.getCOSObject().setString("PFWatermark",settings.toString());
            com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream appearance=new com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream(document);appearance.setBBox(new PDRectangle(box.getWidth(),box.getHeight()));appearance.setResources(new com.tom_roush.pdfbox.pdmodel.PDResources());
            try(PDPageContentStream stream=new PDPageContentStream(document,appearance)){stream.drawImage(LosslessFactory.createFromImage(document,bitmap),matrix);}
            com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary dictionary=new com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary();dictionary.setNormalAppearance(appearance);mark.setAppearance(dictionary);page.getAnnotations().add(mark);
        } finally { bitmap.recycle(); }
    }
}
