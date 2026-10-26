package com.example.myapplication;

import android.content.Context;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Common editor chrome, preserving captions and per-screen actions. */
final class EditorViews {
    static LinearLayout root(Context context){LinearLayout root=new LinearLayout(context);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(AppTheme.background());return root;}
    static LinearLayout header(Context context){LinearLayout row=new LinearLayout(context);row.setGravity(Gravity.CENTER_VERTICAL);row.setBackgroundColor(AppTheme.surface());return row;}
    static TextView text(Context context,String value,int size,int color){TextView view=new TextView(context);view.setText(value);view.setTextSize(size);view.setTextColor(color);view.setGravity(Gravity.CENTER_VERTICAL);return view;}
    static void addBack(LinearLayout header,int color,Runnable action){
        Context context=header.getContext();float density=context.getResources().getDisplayMetrics().density;
        ImageView back=new ImageView(context);back.setImageDrawable(new AppIcon("Back",color));int padding=Math.round(14*density);back.setPadding(padding,padding,padding,padding);
        back.setContentDescription(UiText.get(R.string.ui_back));header.addView(back,new LinearLayout.LayoutParams(Math.round(50*density),Math.round(56*density)));back.setOnClickListener(v->action.run());
    }
}
