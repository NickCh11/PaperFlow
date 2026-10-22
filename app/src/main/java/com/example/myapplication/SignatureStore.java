package com.example.myapplication;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.net.Uri;
import android.media.ExifInterface;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** Private, local reusable marks. No document or signature is uploaded. */
final class SignatureStore {
    private SignatureStore() { }
    static File directory(Context context) throws Exception {
        File folder = new File(context.getFilesDir(), "signatures");
        if (!folder.isDirectory() && !folder.mkdirs()) throw new IllegalStateException(UiText.get(R.string.ui_storage_unavailable));
        return folder;
    }
    static ArrayList<File> list(Context context, boolean stamp) {
        File[] files = new File(context.getFilesDir(), "signatures").listFiles((dir, name) -> name.startsWith(stamp ? "stamp-" : "signature-") && name.endsWith(".png"));
        ArrayList<File> result = new ArrayList<>();
        if (files != null) { Arrays.sort(files, (a,b) -> Long.compare(b.lastModified(), a.lastModified())); result.addAll(Arrays.asList(files)); }
        return result;
    }
    static File save(Context context, Bitmap mark, boolean stamp) throws Exception {
        File output = new File(directory(context), (stamp ? "stamp-" : "signature-") + UUID.randomUUID() + ".png");
        try (FileOutputStream stream = new FileOutputStream(output)) { if (!mark.compress(Bitmap.CompressFormat.PNG, 100, stream)) throw new IllegalStateException(UiText.get(R.string.ui_unable_to_save_mark)); }
        catch (Exception error) { output.delete(); throw error; }
        return output;
    }
    static File saveTemporaryDate(Context context, Bitmap mark) throws Exception {
        File folder = new File(context.getFilesDir(), "signingtemp");
        if (!folder.isDirectory() && !folder.mkdirs()) throw new IllegalStateException(UiText.get(R.string.ui_storage_unavailable));
        File output = new File(folder, "date-" + UUID.randomUUID() + ".png");
        try (FileOutputStream stream = new FileOutputStream(output)) { if (!mark.compress(Bitmap.CompressFormat.PNG, 100, stream)) throw new IllegalStateException(UiText.get(R.string.ui_unable_to_save_date)); }
        catch (Exception error) { output.delete(); throw error; }
        return output;
    }
    static boolean isOwnedTemporaryDate(Context context, String path) {
        if (path == null) return false;
        try {
            File root = new File(context.getFilesDir(), "signingtemp").getCanonicalFile();
            File candidate = new File(path).getCanonicalFile();
            return root.equals(candidate.getParentFile()) && candidate.getName().startsWith("date-") && candidate.getName().endsWith(".png");
        } catch (Exception ignored) { return false; }
    }
    static void removeTemporaryDates(Context context, List<String> paths) {
        for (String path : paths) if (isOwnedTemporaryDate(context, path)) {
            try { new File(path).getCanonicalFile().delete(); } catch (Exception ignored) { }
        }
    }
    static Bitmap importMark(Context context, Uri uri) throws Exception {
        BitmapFactory.Options size = new BitmapFactory.Options(); size.inJustDecodeBounds = true;
        try (InputStream in = context.getContentResolver().openInputStream(uri)) { BitmapFactory.decodeStream(in, null, size); }
        if (size.outWidth <= 0 || size.outHeight <= 0) throw new IllegalArgumentException(UiText.get(R.string.ui_select_an_image));
        BitmapFactory.Options options = new BitmapFactory.Options(); options.inSampleSize = 1;
        while (Math.max(size.outWidth, size.outHeight) / options.inSampleSize > 1800) options.inSampleSize *= 2;
        Bitmap source;
        try (InputStream in = context.getContentResolver().openInputStream(uri)) { source = BitmapFactory.decodeStream(in, null, options); }
        if (source == null) throw new IllegalArgumentException(UiText.get(R.string.ui_could_not_read_image));
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            int orientation = new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            Matrix matrix = new Matrix();
            switch (orientation) {
                case ExifInterface.ORIENTATION_FLIP_HORIZONTAL: matrix.setScale(-1,1); break;
                case ExifInterface.ORIENTATION_ROTATE_180: matrix.setRotate(180); break;
                case ExifInterface.ORIENTATION_FLIP_VERTICAL: matrix.setScale(1,-1); break;
                case ExifInterface.ORIENTATION_TRANSPOSE: matrix.setRotate(90); matrix.postScale(-1,1); break;
                case ExifInterface.ORIENTATION_ROTATE_90: matrix.setRotate(90); break;
                case ExifInterface.ORIENTATION_TRANSVERSE: matrix.setRotate(270); matrix.postScale(-1,1); break;
                case ExifInterface.ORIENTATION_ROTATE_270: matrix.setRotate(270); break;
            }
            if (!matrix.isIdentity()) { Bitmap corrected = Bitmap.createBitmap(source,0,0,source.getWidth(),source.getHeight(),matrix,true); if (corrected != source) source.recycle(); source = corrected; }
        } catch (Exception ignored) { /* Image format may not carry EXIF. */ }
        try { return trimBackground(source); } finally { source.recycle(); }
    }
    /** Only copied camera inputs inside our capture directory may be removed. */
    static void removeOwnedCaptures(Context context, List<String> candidates) {
        if (candidates == null) return;
        try {
            String root = new File(context.getFilesDir(), "captures").getCanonicalPath() + File.separator;
            for (String value : candidates) {
                if (value == null) continue;
                try {
                    Uri uri = Uri.parse(value);
                    String scheme = uri.getScheme();
                    if (scheme != null && !scheme.equals("file")) continue;
                    String path = scheme == null ? value : uri.getPath();
                    if (path == null) continue;
                    File candidate = new File(path).getCanonicalFile();
                    if (candidate.getPath().startsWith(root) && candidate.isFile()) candidate.delete();
                } catch (Exception ignored) { /* A failed cleanup never invalidates the durable signature. */ }
            }
        } catch (Exception ignored) { }
    }
    static Bitmap trimBackground(Bitmap source) throws Exception {
        int width=source.getWidth(),height=source.getHeight(); int[] pixels=new int[width*height]; source.getPixels(pixels,0,width,0,0,width,height);
        int left=width,top=height,right=-1,bottom=-1;
        for(int y=0;y<height;y++) for(int x=0;x<width;x++) {
            int i=y*width+x,c=pixels[i], minimum=Math.min(Color.red(c),Math.min(Color.green(c),Color.blue(c)));
            int alpha=Color.alpha(c)*Math.min(255,Math.max(0,(248-minimum)*255/60))/255;
            pixels[i]=(c&0xffffff)|(alpha<<24);
            if(alpha>24){left=Math.min(left,x);right=Math.max(right,x);top=Math.min(top,y);bottom=Math.max(bottom,y);}
        }
        if(right<left) throw new IllegalArgumentException(UiText.get(R.string.ui_no_signature_found_use_dark_ink_on_a_plain_white_background));
        Bitmap all=Bitmap.createBitmap(pixels,width,height,Bitmap.Config.ARGB_8888);
        left=Math.max(0,left-6);top=Math.max(0,top-6);right=Math.min(width-1,right+6);bottom=Math.min(height-1,bottom+6);
        Bitmap cropped=Bitmap.createBitmap(all,left,top,right-left+1,bottom-top+1);if(cropped!=all)all.recycle();return cropped;
    }
}
