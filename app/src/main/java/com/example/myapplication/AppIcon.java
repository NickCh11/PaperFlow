package com.example.myapplication;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import androidx.core.graphics.PathParser;

/** Original, scalable 24-unit icon family with consistent rounded strokes. */
final class AppIcon extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final String path;

    AppIcon(String name, int color) {
        paint.setColor(color);
        paint.setStyle("All Features".equals(name)?Paint.Style.FILL:Paint.Style.STROKE);
        paint.setStrokeWidth(1.7f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        path = pathFor(name);
    }

    @Override public void draw(Canvas canvas) {
        int save = canvas.save();
        float side = Math.min(getBounds().width(), getBounds().height());
        canvas.translate(getBounds().left + (getBounds().width()-side)/2, getBounds().top + (getBounds().height()-side)/2);
        canvas.scale(side/24f, side/24f);
        canvas.drawPath(PathParser.createPathFromPathData(path), paint);
        canvas.restoreToCount(save);
    }
    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }

    private static String pathFor(String name) {
        switch (name) {
            case "All Features": return "M3 3H7V7H3ZM10 3H14V7H10ZM17 3H21V7H17ZM3 10H7V14H3ZM10 10H14V14H10ZM17 10H21V14H17ZM3 17H7V21H3ZM10 17H14V21H10ZM17 17H21V21H17Z";
            case "Home": return "M3 10L12 3L21 10M5 9V20H10V14H14V20H19V9";
            case "All Documents": return "M3 8V20H21V8ZM3 8V5H10L12 8M7 12H17M7 16H13";
            case "Tools": case "All Tools": return "M4 4H10V10H4ZM15 4H21V10H15ZM4 15H10V21H4ZM18 14V22M14 18H22";
            case "Settings": return "M9 3H15L16 6L19 7L22 10V14L19 17L16 18L15 21H9L8 18L5 17L2 14V10L5 7L8 6ZM16 12A4 4 0 1 1 8 12A4 4 0 1 1 16 12";
            case "Me": return "M16 7A4 4 0 1 1 8 7A4 4 0 1 1 16 7M4 21V19C4 12 20 12 20 19V21";
            case "Search": return "M17 10A7 7 0 1 1 3 10A7 7 0 1 1 17 10M15 15L21 21";
            case "Cloud": return "M7 18H18C24 18 23 9 18 10C17 2 6 3 6 11C0 10 1 18 7 18M9 13L12 10L15 13M12 10V20";
            case "Camera": return "M3 7H7L9 4H15L17 7H21V20H3ZM16 13A4 4 0 1 1 8 13A4 4 0 1 1 16 13";
            case "Smart Scan": case "Scan Code": return "M3 8V3H8M16 3H21V8M21 16V21H16M8 21H3V16M7 7H10V10H7ZM14 7H17V10H14ZM7 14H10V17H7ZM14 14H17V17H20M14 17V20";
            case "ID Cards": return "M3 5H21V19H3ZM10 10A2 2 0 1 1 6 10A2 2 0 1 1 10 10M5 16C5 12 11 12 11 16M14 10H18M14 14H18";
            case "ID Photo Maker": return "M4 8V3H9M15 3H20V8M20 16V21H15M9 21H4V16M15 9A3 3 0 1 1 9 9A3 3 0 1 1 15 9M7 18V17C7 12 17 12 17 17V18";
            case "Import Images": case "PDF to Images": return "M3 5H21V20H3ZM3 16L9 10L15 17L18 14L21 17M17 9H17.01";
            case "Import Files": return "M3 8V20H21V8H3V4H10L13 8M12 10V17M9 14L12 17L15 14";
            case "Rotate PDF": case "Rotate": case "Undo": return "M3 9C6 1 19 3 21 11C23 19 12 24 7 18M3 3V9H9";
            case "Rotate Right": return "M21 9C18 1 5 3 3 11C1 19 12 24 17 18M21 3V9H15";
            case "Merge PDFs": case "Merge Files": return "M3 3H9V10H3ZM15 3H21V10H15ZM6 10V14L12 18L18 14V10M12 15V22M9 19L12 22L15 19";
            case "Formula": return "M3 13L6 17L10 5H21M14 10L20 16M20 10L14 16";
            case "Photo Translation": return "M3 5H13M8 3V5M11 5C11 11 7 14 3 15M5 8C6 11 9 14 12 15M13 21L17 10L21 21M15 17H19";
            case "Book": return "M12 6C9 3 5 3 2 4V19C5 18 9 18 12 21C15 18 19 18 22 19V4C19 3 15 3 12 6V21";
            case "Whiteboard": return "M3 4H21V17H3ZM7 21L9 17M17 21L15 17M6 9H13M6 12H10M15 14L19 10";
            case "Slides": return "M3 4H21V16H3ZM12 16V21M8 22L12 19L16 22M7 12L10 9L13 11L17 7";
            case "Timestamp": return "M21 12A9 9 0 1 1 3 12A9 9 0 1 1 21 12M12 6V12L16 14";
            case "To Word": return "M5 3H15L20 8V21H5ZM15 3V8H20M8 11L10 17L12 13L14 17L16 11";
            case "Office to PDF":case "Batch OCR":case "Conversion": return "M10 3L12 9L18 11L12 13L10 20L8 13L2 11L8 9ZM19 2L20 5L23 6L20 7L19 10L18 7L15 6L18 5Z";
            case "Page Count": return "M4 3H15V19H4ZM9 6H20V22H9V19M15 6V19";
            case "To Excel": return "M5 3H15L20 8V21H5ZM15 3V8H20M8 11L12 17M12 11L8 17M15 12H17M15 16H17";
            case "To PowerPoint":case "To PPT": return "M5 3H15L20 8V21H5ZM15 3V8H20M9 18V11H13C17 11 17 15 13 15H9";
            case "PDF to Long Image": return "M6 2H18V22H6ZM9 6H15M9 10H15M9 14H15M9 18H15";
            case "Edit": return "M4 17L5 12L16 2L21 7L10 18L4 19ZM14 4L19 9M3 22H21";
            case "Sign": case "Signature": return "M3 20C6 15 9 5 17 3C22 1 24 4 19 8C16 10 12 11 9 11M9 11L6 17C10 14 13 13 15 14C17 15 13 17 11 18H20M9 21H20";
            case "Add Watermark": return "M8 3H16V9L19 14V17H5V14L8 9ZM3 21H21M8 9H16";
            case "Smart Erase": return "M3 16L12 7L18 13L10 21H8ZM7 12L13 18M17 2V8M14 5H20M20 17V21M18 19H22";
            case "Erase Marks": return "M3 14L13 4L21 12L12 21H9ZM8 9L16 17M12 21H22";
            case "Restore Photo": return "M3 7V20H21V7M3 15L8 10L14 17L18 13L21 16M12 3V9M9 6H15M19 2V6M17 4H21";
            case "Extract PDF Pages": return "M4 3H14V7M4 3V17H8M9 8H20V22H9ZM12 12H17M12 16H17";
            case "Reorder Pages": case "Move left": case "Move right": return "M7 3V21M3 7L7 3L11 7M17 21V3M13 17L17 21L21 17";
            case "Lock": return "M5 11H19V21H5ZM8 11V7C8 1 16 1 16 7V11M12 15V17";
            case "Compress": return "M3 3L9 9M9 4V9H4M21 3L15 9M15 4V9H20M3 21L9 15M4 15H9V20M21 21L15 15M20 15H15V20";
            case "Print": return "M7 8V3H17V8M6 17H3V8H21V17H18M7 14H17V22H7ZM17 11H18";
            case "Share": return "M8 10.5L16 6M8 13.5L16 18M9 12A3 3 0 1 1 3 12A3 3 0 1 1 9 12M22 4.5A3 3 0 1 1 16 4.5A3 3 0 1 1 22 4.5M22 19.5A3 3 0 1 1 16 19.5A3 3 0 1 1 22 19.5";
            case "More": return "M13 4A1 1 0 1 1 11 4A1 1 0 1 1 13 4M13 12A1 1 0 1 1 11 12A1 1 0 1 1 13 12M13 20A1 1 0 1 1 11 20A1 1 0 1 1 13 20";
            case "View": return "M2 12C7 3 17 3 22 12C17 21 7 21 2 12ZM15 12A3 3 0 1 1 9 12A3 3 0 1 1 15 12";
            case "Close": return "M5 5L19 19M19 5L5 19";
            case "Back": return "M20 12H4M11 5L4 12L11 19";
            case "Previous": return "M15 4L7 12L15 20";
            case "Next": return "M9 4L17 12L9 20";
            case "Down": return "M5 9L12 16L19 9";
            case "Done": return "M4 12L10 18L21 6";
            case "Delete": return "M3 6H21M9 6V3H15V6M5 6L6 21H18L19 6M10 10V17M14 10V17";
            case "Filter": return "M12 3A9 9 0 1 1 12 21A9 9 0 1 1 12 3ZM12 3V21M15 6V18M18 9V15";
            case "Crop": return "M6 2V18H22M2 6H18V22M10 6H18V14";
            default: return "M5 3H15L20 8V21H5ZM15 3V8H20M8 12H16M8 16H14";
        }
    }
}
