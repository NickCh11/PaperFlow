package com.example.myapplication;

import android.app.Application;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.*;
import androidx.lifecycle.*;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;

public class LanguageManagerActivity extends AppCompatActivity {
    private String kind;private LinearLayout rows;private TextView progress;private DownloadSession session;
    public static class DownloadSession extends AndroidViewModel {
        final ExecutorService worker=Executors.newSingleThreadExecutor();final MutableLiveData<String> status=new MutableLiveData<>("");volatile boolean cancelled,busy;
        public DownloadSession(Application app){super(app);}
        void start(String kind,JSONObject entry){if(busy)return;busy=true;cancelled=false;status.setValue(UiText.format(R.string.ui_language_download_progress,LanguageModels.title(entry),0));worker.execute(()->{String message;try{LanguageModels.download(getApplication(),kind,entry,()->cancelled,(bytes,total)->{if(!cancelled)status.postValue(UiText.format(R.string.ui_language_download_progress,LanguageModels.title(entry),Math.min(100,bytes*100/Math.max(1,total))));});message=UiText.get(R.string.ui_download_complete);}catch(Exception e){message=cancelled?UiText.get(R.string.ui_language_download_cancelled):e.getMessage();}busy=false;status.postValue(message);});}
        void cancel(){if(!busy)return;cancelled=true;status.setValue(UiText.get(R.string.ui_language_download_cancelling));}
        @Override protected void onCleared(){cancelled=true;worker.shutdownNow();}
        void updates(){if(busy)return;busy=true;cancelled=false;status.setValue(UiText.get(R.string.ui_updating_language_packs));worker.execute(()->{String message;try{com.paperflow.translation.OfflineRuntime.get(getApplication()).checkUpdates(()->cancelled,(bytes,total)->status.postValue(total>0?UiText.format(R.string.ui_downloading_value_value,UiText.get(R.string.ui_languages),bytes*100/total):UiText.get(R.string.ui_updating_language_packs)));message=UiText.get(R.string.ui_download_complete);}catch(Exception error){message=error.getMessage();}busy=false;status.postValue(message);});}
    }
    @Override public void onCreate(Bundle state){super.onCreate(state);kind="translation".equals(getIntent().getStringExtra("kind"))?"translation":"ocr";session=new ViewModelProvider(this).get(DownloadSession.class);
        LinearLayout root=new LinearLayout(this);root.setOrientation(android.widget.LinearLayout.VERTICAL);root.setPadding(dp(20),dp(12),dp(20),dp(12));root.setBackgroundColor(AppTheme.background());
        TextView back=new TextView(this);back.setText("‹  "+getString(R.string.ui_language_manager_title));back.setTextSize(20);back.setTextColor(AppTheme.ink());back.setGravity(Gravity.CENTER_VERTICAL);back.setMinHeight(dp(48));back.setOnClickListener(v->finish());root.addView(back);
        TextView help=new TextView(this);help.setText(R.string.ui_shared_language_packs_help);help.setPadding(8,12,8,12);root.addView(help);
        progress=new TextView(this);progress.setPadding(0,dp(8),0,dp(8));root.addView(progress);Button cancel=new androidx.appcompat.widget.AppCompatButton(this,null,androidx.appcompat.R.attr.borderlessButtonStyle);cancel.setText(UiText.get(R.string.ui_cancel_download));cancel.setAllCaps(false);cancel.setOnClickListener(v->session.cancel());root.addView(cancel);
        Button updates=new androidx.appcompat.widget.AppCompatButton(this,null,androidx.appcompat.R.attr.borderlessButtonStyle);updates.setText(R.string.ui_language_manager_updates);updates.setAllCaps(false);updates.setOnClickListener(v->session.updates());root.addView(updates);
        ScrollView scroll=new ScrollView(this);rows=new LinearLayout(this);rows.setOrientation(android.widget.LinearLayout.VERTICAL);scroll.addView(rows);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));UiInsets.apply(root);setContentView(root);
        session.status.observe(this,value->{progress.setText(value);progress.setVisibility(value==null||value.isEmpty()?View.GONE:View.VISIBLE);cancel.setVisibility(session.busy?View.VISIBLE:View.GONE);cancel.setEnabled(!session.cancelled);updates.setEnabled(!session.busy);if(!session.busy)render();});render();
    }
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private void render(){rows.removeAllViews();try{
        JSONArray list=com.paperflow.translation.OfflineRuntime.get(this).languages();ArrayList<JSONObject> sorted=new ArrayList<>();for(int i=0;i<list.length();i++)sorted.add(list.getJSONObject(i));sorted.sort(Comparator.comparingInt((JSONObject e)->e.optString("code").equals("el")?0:e.optString("code").equals("en")?1:2).thenComparing(LanguageModels::title));
        for(JSONObject entry:sorted){String code=entry.getString("code");boolean installed=entry.optBoolean("installed");LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(0,8,0,8);
            CheckBox label=new CheckBox(this);label.setText(LanguageModels.title(entry)+" · "+String.format(Locale.ROOT,"%.1f MB",entry.getLong("size")/1000000.0));label.setTextSize(15);label.setMinHeight(dp(56));label.setEnabled(entry.optBoolean("ocr"));label.setChecked(getSharedPreferences("local-languages",0).getStringSet("ocr-enabled",Collections.emptySet()).contains(code));
            label.setOnCheckedChangeListener((button,checked)->LanguageModels.setOcrEnabled(this,code,checked));row.addView(label,new LinearLayout.LayoutParams(0,-2,1));
            Button action=new androidx.appcompat.widget.AppCompatButton(this,null,androidx.appcompat.R.attr.borderlessButtonStyle);action.setAllCaps(false);action.setMinWidth(0);action.setMinimumWidth(0);action.setTextSize(13);action.setText(installed ? UiText.get(R.string.ui_delete) : UiText.get(R.string.ui_download));action.setOnClickListener(v->{if(session.busy)return;if(installed)new AlertDialog.Builder(this).setMessage(UiText.format(R.string.ui_delete_value, LanguageModels.title(entry))).setNegativeButton(UiText.get(R.string.ui_cancel),null).setPositiveButton(UiText.get(R.string.ui_delete),(d,w)->{try{LanguageModels.remove(this,kind,entry);render();}catch(Exception e){progress.setText(e.getMessage());}}).show();else session.start(kind,entry);});row.addView(action);rows.addView(row);
        }
    }catch(Exception e){progress.setText(UiText.format(R.string.ui_could_not_load_language_catalog_value, e.getMessage()));}}
}
