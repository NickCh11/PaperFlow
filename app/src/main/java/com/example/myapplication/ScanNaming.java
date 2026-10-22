package com.example.myapplication;

import android.content.*;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import java.text.SimpleDateFormat;
import java.util.*;

final class ScanNaming {
    static synchronized String next(Context c){android.content.SharedPreferences p=c.getSharedPreferences("scan_defaults",0);if(!p.getBoolean("auto_name",true))return "Untitled";long n=p.getLong("name_counter",0)+1;p.edit().putLong("name_counter",n).commit();Date now=new Date();String date=new SimpleDateFormat("yyyy-MM-dd",Locale.ROOT).format(now),time=new SimpleDateFormat("HH.mm.ss",Locale.ROOT).format(now),prefix=p.getString("prefix","PaperFlow");
        String pattern=p.getString("name_pattern","{prefix} {date} {time}");String name=pattern.replace("{prefix}",prefix).replace("{date}",date).replace("{time}",time).replace("{n}",String.format(Locale.ROOT,"%04d",n)).trim();return name.isEmpty()?"Document "+n:name;
    }
    static void configure(AppCompatActivity a){android.content.SharedPreferences p=a.getSharedPreferences("scan_defaults",0);LinearLayout body=new LinearLayout(a);body.setOrientation(android.widget.LinearLayout.VERTICAL);int pad=Math.round(18*a.getResources().getDisplayMetrics().density);body.setPadding(pad,0,pad,0);CheckBox auto=new CheckBox(a);auto.setText(UiText.get(R.string.ui_automatic_document_naming));auto.setTextColor(AppTheme.ink());auto.setChecked(p.getBoolean("auto_name",true));body.addView(auto);EditText prefix=new EditText(a);prefix.setTextColor(AppTheme.ink());prefix.setHintTextColor(AppTheme.muted());prefix.setHint(UiText.get(R.string.ui_prefix));prefix.setSingleLine();prefix.setText(p.getString("prefix","PaperFlow"));body.addView(prefix);EditText pattern=new EditText(a);pattern.setTextColor(AppTheme.ink());pattern.setHintTextColor(AppTheme.muted());pattern.setSingleLine();pattern.setText(p.getString("name_pattern","{prefix} {date} {time}"));body.addView(pattern);TextView help=new TextView(a);help.setTextColor(AppTheme.muted());help.setText(UiText.get(R.string.ui_use_prefix_date_time_n_example_prefix_n_with_automatic_naming_off_you_choos));body.addView(help);
        androidx.appcompat.app.AlertDialog dialog=new ProtectedDialogs.Builder(a).setTitle(UiText.get(R.string.ui_new_document_naming)).setView(body).setNegativeButton(UiText.get(R.string.ui_cancel),null).setPositiveButton(UiText.get(R.string.ui_save),null).create();dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(v->{String value=pattern.getText().toString().trim();if(auto.isChecked()&&(value.isEmpty()||value.length()>120||value.replaceAll("\\{(prefix|date|time|n)\\}","").matches(".*[{}].*"))){pattern.setError(UiText.get(R.string.ui_use_up_to_120_characters_and_the_supported_placeholders));return;}p.edit().putBoolean("auto_name",auto.isChecked()).putString("prefix",prefix.getText().toString().trim()).putString("name_pattern",value).apply();dialog.dismiss();}));dialog.show();
    }
}
