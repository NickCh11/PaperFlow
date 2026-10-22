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
        void start(String kind,JSONObject entry){if(busy)return;busy=true;cancelled=false;status.setValue("Downloading "+LanguageModels.title(entry));worker.execute(()->{String message;try{LanguageModels.download(getApplication(),kind,entry,()->cancelled,(bytes,total)->status.postValue(LanguageModels.title(entry)+" · "+bytes*100/Math.max(1,total)+"%"));message=UiText.get(R.string.ui_download_complete);}catch(Exception e){message=e.getMessage();}busy=false;status.postValue(message);});}
        @Override protected void onCleared(){cancelled=true;worker.shutdownNow();}
    }
    @Override public void onCreate(Bundle state){super.onCreate(state);kind="translation".equals(getIntent().getStringExtra("kind"))?"translation":"ocr";session=new ViewModelProvider(this).get(DownloadSession.class);
        LinearLayout root=new LinearLayout(this);root.setOrientation(android.widget.LinearLayout.VERTICAL);root.setPadding(24,24,24,24);root.setBackgroundColor(AppTheme.background());
        Button back=new Button(this);back.setText(UiText.format(R.string.ui_value_bf1b9b3a, (kind.equals("ocr")?"OCR languages":"Translation languages")));back.setOnClickListener(v->finish());root.addView(back);
        TextView help=new TextView(this);help.setText(kind.equals("ocr") ? UiText.get(R.string.ui_download_languages_then_enable_the_ones_to_recognize_active_languages_add_s) : UiText.get(R.string.ui_download_the_translation_directions_you_need_after_download_translation_wor));help.setPadding(8,12,8,12);root.addView(help);
        progress=new TextView(this);root.addView(progress);Button cancel=new Button(this);cancel.setText(UiText.get(R.string.ui_cancel_download));cancel.setOnClickListener(v->session.cancelled=true);root.addView(cancel);
        ScrollView scroll=new ScrollView(this);rows=new LinearLayout(this);rows.setOrientation(android.widget.LinearLayout.VERTICAL);scroll.addView(rows);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));UiInsets.apply(root);setContentView(root);
        session.status.observe(this,value->{progress.setText(value);cancel.setVisibility(session.busy?View.VISIBLE:View.GONE);if(!session.busy)render();});render();
    }
    private void render(){rows.removeAllViews();try{
        JSONArray list=LanguageModels.catalog(this,kind);ArrayList<JSONObject> sorted=new ArrayList<>();for(int i=0;i<list.length();i++)sorted.add(list.getJSONObject(i));sorted.sort(Comparator.comparingInt((JSONObject e)->e.optString("code").equals("ell")?0:e.optString("code").equals("eng")?1:2).thenComparing(LanguageModels::title));
        for(JSONObject entry:sorted){String code=entry.getString("code");boolean installed=LanguageModels.installed(this,kind,entry);LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(0,8,0,8);
            CheckBox label=new CheckBox(this);label.setText(UiText.format(R.string.ui_value_value_c5cbdde4, LanguageModels.title(entry), String.format(Locale.ROOT,"%.1f MB",entry.getLong("size")/1000000.0)));label.setEnabled(installed&&kind.equals("ocr"));label.setChecked(kind.equals("ocr")&&getSharedPreferences("local-languages",0).getStringSet("ocr-enabled",Collections.emptySet()).contains(code));
            label.setOnCheckedChangeListener((button,checked)->{Set<String> selected=new HashSet<>(getSharedPreferences("local-languages",0).getStringSet("ocr-enabled",Collections.emptySet()));if(checked)selected.add(code);else selected.remove(code);getSharedPreferences("local-languages",0).edit().putStringSet("ocr-enabled",selected).apply();});row.addView(label,new LinearLayout.LayoutParams(0,-2,1));
            Button action=new Button(this);action.setText(installed ? UiText.get(R.string.ui_delete) : UiText.get(R.string.ui_download));action.setOnClickListener(v->{if(session.busy)return;if(installed)new AlertDialog.Builder(this).setMessage(UiText.format(R.string.ui_delete_value, LanguageModels.title(entry))).setNegativeButton(UiText.get(R.string.ui_cancel),null).setPositiveButton(UiText.get(R.string.ui_delete),(d,w)->{try{LanguageModels.remove(this,kind,entry);render();}catch(Exception e){progress.setText(e.getMessage());}}).show();else session.start(kind,entry);});row.addView(action);rows.addView(row);
        }
    }catch(Exception e){progress.setText(UiText.format(R.string.ui_could_not_load_language_catalog_value, e.getMessage()));}}
}
