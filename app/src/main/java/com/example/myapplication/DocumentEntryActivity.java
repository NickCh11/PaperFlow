package com.example.myapplication;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import java.util.ArrayList;

/** Public import boundary. Document commands belong only to non-exported MainActivity. */
public final class DocumentEntryActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        try { startActivity(sanitize(this,getIntent())); }
        catch (RuntimeException invalid) { /* Malformed parcelables/provider grants must not crash the entry point. */ }
        finish();
    }

    static Intent sanitize(android.content.Context context,Intent incoming) {
        Intent target = new Intent(context, MainActivity.class);
        String action = incoming.getAction();
        if (Intent.ACTION_MAIN.equals(action)) return target.setAction(Intent.ACTION_MAIN);
        ArrayList<Uri> uris = new ArrayList<>();
        if (Intent.ACTION_VIEW.equals(action)) add(context,uris, incoming.getData());
        else if (Intent.ACTION_SEND.equals(action)) {
            Object stream = incoming.getParcelableExtra(Intent.EXTRA_STREAM);
            if (stream instanceof Uri) add(context,uris, (Uri) stream);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            ArrayList<?> streams = incoming.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (streams != null) for (Object stream : streams) {
                if (stream instanceof Uri) add(context,uris, (Uri) stream);
                if (uris.size() >= 100) break;
            }
        }
        if (uris.isEmpty()) return target.setAction(Intent.ACTION_MAIN);
        target.setAction(action);
        if (Intent.ACTION_VIEW.equals(action)) target.setDataAndType(uris.get(0), incoming.getType());
        else {
            target.setType(incoming.getType());
            if (Intent.ACTION_SEND_MULTIPLE.equals(action)) target.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
            else target.putExtra(Intent.EXTRA_STREAM, uris.get(0));
        }
        ClipData grants = ClipData.newRawUri("Imported documents", uris.get(0));
        for (int i = 1; i < uris.size(); i++) grants.addItem(new ClipData.Item(uris.get(i)));
        target.setClipData(grants);
        return target.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    }

    private static void add(android.content.Context context,ArrayList<Uri> uris, Uri uri) {
        if (uri == null || !"content".equals(uri.getScheme())) return;
        String authority=uri.getAuthority();if(authority==null)return;
        // Android can qualify a content authority as userId@authority. Compare
        // the provider itself so a user prefix cannot disguise our private files.
        String provider=authority.substring(authority.lastIndexOf('@')+1);
        if (!provider.isEmpty() && !(context.getPackageName()+".files").equals(provider) && !uris.contains(uri)) uris.add(uri);
    }
}
