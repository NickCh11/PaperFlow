package com.example.myapplication;

import android.content.*;
import android.widget.*;
import androidx.appcompat.app.*;

final class ScanImportOptions {
    interface Choice {void apply(boolean crop,int filter);}
    static void show(AppCompatActivity activity,Choice choice,Runnable cancel){
        SharedPreferences prefs=activity.getSharedPreferences("scan_defaults",0);LinearLayout content=new LinearLayout(activity);content.setOrientation(android.widget.LinearLayout.VERTICAL);content.setPadding(24,12,24,12);
        CheckBox crop=new CheckBox(activity);crop.setText(UiText.get(R.string.ui_detect_and_crop_every_page_automatically));crop.setChecked(prefs.getBoolean("import_crop",true));content.addView(crop);
        TextView label=new TextView(activity);label.setText(UiText.get(R.string.ui_filter_for_all_imported_pages));label.setTextColor(AppTheme.ink());content.addView(label);Spinner filter=new Spinner(activity);filter.setAdapter(new ArrayAdapter<>(activity,android.R.layout.simple_spinner_dropdown_item,new String[]{"Default","PaperScan Pro"}));filter.setSelection(prefs.getInt("import_filter",prefs.getInt("filter",1)));content.addView(filter);
        new AlertDialog.Builder(activity).setTitle(UiText.get(R.string.ui_import_images_6d78400e)).setView(content).setNegativeButton(UiText.get(R.string.ui_cancel),(d,w)->cancel.run()).setOnCancelListener(d->cancel.run()).setPositiveButton(UiText.get(R.string.ui_import),(d,w)->{int value=filter.getSelectedItemPosition();prefs.edit().putBoolean("import_crop",crop.isChecked()).putInt("import_filter",value).apply();choice.apply(crop.isChecked(),value);}).show();
    }
}
