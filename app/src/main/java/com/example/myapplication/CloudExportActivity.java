package com.example.myapplication;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.widget.*;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import java.io.*;
import java.util.ArrayList;

/** Manual exports use the user's installed document providers; no account or server is involved. */
public class CloudExportActivity extends BackHandlingActivity {
    private ArrayList<String> paths,names;
    private ActivityResultLauncher<String> filePicker;
    private ActivityResultLauncher<Uri> folderPicker;
    private TextView status;
    private boolean busy;
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        paths=getIntent().getStringArrayListExtra("paths");names=getIntent().getStringArrayListExtra("names");
        if(paths==null||names==null||paths.isEmpty()||paths.size()!=names.size()){finish();return;}
        filePicker=registerForActivityResult(new ActivityResultContracts.CreateDocument("application/pdf"),uri->{if(uri!=null)write(uri,false);});
        folderPicker=registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(),uri->{if(uri!=null)write(uri,true);});
        LinearLayout root=new LinearLayout(this);root.setOrientation(android.widget.LinearLayout.VERTICAL);root.setPadding(24,24,24,24);root.setBackgroundColor(AppTheme.surface());UiInsets.apply(root);setContentView(root);
        TextView title=new TextView(this);title.setText(UiText.get(R.string.ui_save_to_cloud));title.setTextSize(24);root.addView(title);
        status=new TextView(this);status.setText(UiText.format(R.string.ui_value_document_s_selected_choose_google_drive_onedrive_or_another_destinati, paths.size()));status.setPadding(0,24,0,24);root.addView(status);
        Button choose=new Button(this);choose.setText(paths.size()==1 ? UiText.get(R.string.ui_choose_destination) : UiText.get(R.string.ui_choose_destination_folder));root.addView(choose);choose.setOnClickListener(v->{if(!busy){if(paths.size()==1)filePicker.launch(filename(names.get(0)));else folderPicker.launch(null);}});
        Button close=new Button(this);close.setText(UiText.get(R.string.ui_done));root.addView(close);close.setOnClickListener(v->{if(!busy)finish();});
    }
    @Override public void handleBackPressed(){if(!busy)super.handleBackPressed();}
    private String filename(String name){String value=name.replaceAll("[\\\\/:*?\"<>|]","_");return value.toLowerCase(java.util.Locale.ROOT).endsWith(".pdf")?value:value+".pdf";}
    private void write(Uri destination,boolean tree){
        for(String path:paths)if(!FolderLock.ensureDocument(this,path,()->write(destination,tree)))return;
        busy=true;setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED);status.setText(UiText.get(R.string.ui_saving));
        new Thread(()->{int saved=0;Uri incomplete=null;try{
            Uri parent=tree?DocumentsContract.buildDocumentUriUsingTree(destination,DocumentsContract.getTreeDocumentId(destination)):null;
            for(int i=0;i<paths.size();i++){
                Uri target=tree?DocumentsContract.createDocument(getContentResolver(),parent,"application/pdf",filename(names.get(i))):destination;
                if(target==null)throw new IOException(UiText.get(R.string.ui_the_destination_could_not_create_a_file));incomplete=target;
                try(InputStream in=new FileInputStream(paths.get(i));OutputStream out=getContentResolver().openOutputStream(target,"w")){
                    if(out==null)throw new IOException(UiText.get(R.string.ui_the_destination_is_unavailable));byte[] buffer=new byte[32768];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);
                }
                incomplete=null;saved++;
            }
            int count=saved;runOnUiThread(()->{busy=false;setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);status.setText(UiText.format(R.string.ui_value_document_s_saved_to_the_selected_destination_your_cloud_app_handles_u, count));});
        }catch(Exception e){if(incomplete!=null)try{DocumentsContract.deleteDocument(getContentResolver(),incomplete);}catch(Exception ignored){}int count=saved;runOnUiThread(()->{busy=false;setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);status.setText(UiText.format(R.string.ui_value_document_s_saved_could_not_finish_value_check_the_destination_and_ava, count, e.getMessage()));});}
        },"manual-cloud-export").start();
    }
}
