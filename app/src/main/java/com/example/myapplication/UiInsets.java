package com.example.myapplication;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import java.util.WeakHashMap;

final class UiInsets {
    private static final WeakHashMap<View, int[]> originalPadding = new WeakHashMap<>();
    static void apply(View root) {
        apply(root,false);
    }
    static void applyKeyboardAware(View root){
        apply(root,true);
    }
    private static void apply(View root,boolean keyboardAware){
        Activity activity=activity(root.getContext());
        if(activity!=null)configure(activity.getWindow(),root,keyboardAware);
        listen(root,keyboardAware,false);
    }
    static void applyBottomSheet(Window window,View root){
        configure(window,root,true);
        listen(root,true,true);
    }
    private static void configure(Window window,View root,boolean keyboardAware){
        WindowCompat.setDecorFitsSystemWindows(window,false);
        if(keyboardAware)window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        if(root.getBackground() instanceof ColorDrawable){
            int color=((ColorDrawable)root.getBackground()).getColor();
            boolean light=(Color.red(color)*.299+Color.green(color)*.587+Color.blue(color)*.114)>150;
            window.setStatusBarColor(color);
            window.setNavigationBarColor(color);
            if(android.os.Build.VERSION.SDK_INT>=29)window.setNavigationBarContrastEnforced(false);
            WindowCompat.getInsetsController(window,window.getDecorView()).setAppearanceLightNavigationBars(light);
            WindowCompat.getInsetsController(window,window.getDecorView()).setAppearanceLightStatusBars(light);
        }
    }
    private static void listen(View root,boolean keyboardAware,boolean bottomSheet){
        int[] base=originalPadding.get(root);
        if(base==null){base=new int[]{root.getPaddingLeft(),root.getPaddingTop(),root.getPaddingRight(),root.getPaddingBottom()};originalPadding.put(root,base);}
        final int[] padding=base;
        ViewCompat.setOnApplyWindowInsetsListener(root,(view,insets)->{
            int types=WindowInsetsCompat.Type.systemBars()|WindowInsetsCompat.Type.displayCutout();
            Insets bars=insets.getInsetsIgnoringVisibility(types);
            int bottom=Math.max(bars.bottom,insets.getInsets(WindowInsetsCompat.Type.mandatorySystemGestures()).bottom);
            if(keyboardAware)bottom=Math.max(bottom,insets.getInsets(WindowInsetsCompat.Type.ime()).bottom);
            view.setPadding(padding[0]+bars.left,padding[1]+(bottomSheet?0:bars.top),padding[2]+bars.right,padding[3]+bottom);
            return insets;
        });
        // Some devices deliver the first insets only after the new content root attaches.
        root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){
            @Override public void onViewAttachedToWindow(View view){
                Activity owner=activity(view.getContext());
                if(owner!=null&&!bottomSheet)configure(owner.getWindow(),view,keyboardAware);
                ViewCompat.requestApplyInsets(view);
            }
            @Override public void onViewDetachedFromWindow(View view){}
        });
        root.post(()->ViewCompat.requestApplyInsets(root));
    }
    private static Activity activity(Context context){
        while(context instanceof ContextWrapper){if(context instanceof Activity)return (Activity)context;Context next=((ContextWrapper)context).getBaseContext();if(next==context)break;context=next;}
        return null;
    }
}
