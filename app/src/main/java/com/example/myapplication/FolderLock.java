package com.example.myapplication;

import android.content.*;
import android.database.Cursor;
import android.os.SystemClock;
import android.text.InputType;
import android.widget.*;
import androidx.appcompat.app.*;
import androidx.biometric.*;
import androidx.core.content.ContextCompat;
import java.security.*;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.util.*;

/** App-level folder access control. Files retain Android private-storage protection; this is not a separate encrypted vault. */
final class FolderLock {
    private static final Map<Long,Long> sessions=new java.util.concurrent.ConcurrentHashMap<>();
    private static final android.os.Handler timer=new android.os.Handler(android.os.Looper.getMainLooper());
    private static final Map<AppCompatActivity,Runnable> watchers=new IdentityHashMap<>();
    private static final Runnable expiry=()->{long now=SystemClock.elapsedRealtime();boolean changed=sessions.entrySet().removeIf(e->e.getValue()<=now);if(changed)notifyViews();schedule();};
    private static void schedule(){timer.removeCallbacks(expiry);long next=Long.MAX_VALUE;for(Long value:sessions.values())next=Math.min(next,value);if(next!=Long.MAX_VALUE)timer.postDelayed(expiry,Math.max(1,next-SystemClock.elapsedRealtime()));}
    private static final Map<AppCompatActivity,ArrayList<java.lang.ref.WeakReference<android.app.Dialog>>> dialogs=new WeakHashMap<>();
    static void trackDialog(AppCompatActivity activity,android.app.Dialog dialog){ArrayList<java.lang.ref.WeakReference<android.app.Dialog>> list=dialogs.computeIfAbsent(activity,a->new ArrayList<>());list.removeIf(ref->ref.get()==null||!ref.get().isShowing());list.add(new java.lang.ref.WeakReference<>(dialog));if((activity.getWindow().getAttributes().flags&android.view.WindowManager.LayoutParams.FLAG_SECURE)!=0&&dialog.getWindow()!=null)dialog.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);}
    static void closeDialogs(AppCompatActivity activity){ArrayList<java.lang.ref.WeakReference<android.app.Dialog>> list=dialogs.remove(activity);if(list!=null)for(java.lang.ref.WeakReference<android.app.Dialog> ref:list){android.app.Dialog dialog=ref.get();if(dialog!=null&&dialog.isShowing())dialog.dismiss();}if(activity instanceof PdfViewerActivity)((PdfViewerActivity)activity).closeTextSelection();}
    private static void notifyViews(){for(Map.Entry<AppCompatActivity,Runnable> entry:new ArrayList<>(watchers.entrySet()))if(!entry.getKey().isDestroyed()&&!entry.getKey().isFinishing())entry.getValue().run();}
    static void watch(AppCompatActivity activity,Runnable refresh){watchers.put(activity,refresh);schedule();}
    static void forget(android.app.Activity activity){watchers.remove(activity);}
    private static void unlocked(long id){sessions.put(id,SystemClock.elapsedRealtime()+300000);timer.post(FolderLock::schedule);}
    private static android.content.SharedPreferences prefs(Context c){return c.getSharedPreferences("folder_locks",0);}
    static boolean protectedFolder(Context c,long id){return prefs(c).contains("pin_"+id);}
    static boolean protectedDocument(Context c,String path){try(DocumentStore store=new DocumentStore(c)){return !store.protectionRoots(path).isEmpty();}}
    static ArrayList<Long> ancestors(Context c,DocumentStore store,long id){ArrayList<Long> roots=new ArrayList<>();Set<Long> seen=new HashSet<>();while(id!=0&&seen.add(id)){if(protectedFolder(c,id))roots.add(0,id);try(Cursor r=store.db().rawQuery("SELECT parent FROM folders WHERE id=?",new String[]{Long.toString(id)})){id=r.moveToFirst()?r.getLong(0):0;}}return roots;}
    static boolean allowed(Context c,DocumentStore store,long id){for(Long root:ancestors(c,store,id))if(sessions.getOrDefault(root,0L)<SystemClock.elapsedRealtime())return false;return true;}
    static String visibleSql(Context c,DocumentStore store){ArrayList<String> hidden=new ArrayList<>();try(Cursor rows=store.db().rawQuery("SELECT id FROM folders",null)){while(rows.moveToNext())if(!allowed(c,store,rows.getLong(0)))hidden.add(rows.getString(0));}if(hidden.isEmpty())return "1=1";String ids=android.text.TextUtils.join(",",hidden);return "folder_id NOT IN ("+ids+") AND path NOT IN (SELECT path FROM document_guards WHERE folder_id IN ("+ids+"))";}
    static boolean ensureDocument(AppCompatActivity a,String path,Runnable again){try(DocumentStore s=new DocumentStore(a)){for(Long root:s.protectionRoots(path))if(sessions.getOrDefault(root,0L)<SystemClock.elapsedRealtime()){authenticate(a,root,again);return false;}return true;}}
    static boolean ensureFolder(AppCompatActivity a,DocumentStore store,long folder,Runnable again){for(Long root:ancestors(a,store,folder)){if(sessions.getOrDefault(root,0L)<SystemClock.elapsedRealtime()){authenticate(a,root,again);return false;}}return true;}
    static void lockAll(){sessions.clear();timer.post(()->{notifyViews();schedule();});}
    static void guard(AppCompatActivity activity,String path){guardAll(activity,Collections.singletonList(path));}
    static void guardAll(AppCompatActivity activity,Collection<String> paths){guardAll(activity,new ArrayList<>(paths),true);}
    private static void guardAll(AppCompatActivity activity,ArrayList<String> paths,boolean prompt){
        String locked=null;boolean protectedContent=false;
        try(DocumentStore store=new DocumentStore(activity)){for(String path:paths)if(path!=null){Set<Long> roots=store.protectionRoots(path);protectedContent|=!roots.isEmpty();for(Long root:roots)if(sessions.getOrDefault(root,0L)<SystemClock.elapsedRealtime()&&locked==null)locked=path;}}
        if(protectedContent){activity.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);watch(activity,()->guardAll(activity,paths,false));}
        android.view.ViewGroup root=activity.findViewById(android.R.id.content);if(root==null||root.getChildCount()==0)return;
        android.view.View content=root.getChildAt(0),existing=root.findViewWithTag("folder-lock-shield");
        if(locked!=null){closeDialogs(activity);
            content.setVisibility(android.view.View.INVISIBLE);
            if(existing==null){LinearLayout shield=new LinearLayout(activity);shield.setTag("folder-lock-shield");shield.setOrientation(android.widget.LinearLayout.VERTICAL);shield.setGravity(android.view.Gravity.CENTER);shield.setBackgroundColor(AppTheme.surface());TextView title=new TextView(activity);title.setText(UiText.get(R.string.ui_protected_document));title.setTextColor(AppTheme.ink());title.setTextSize(20);shield.addView(title);Button unlock=new Button(activity);unlock.setText(UiText.get(R.string.ui_unlock_folder));shield.addView(unlock);unlock.setOnClickListener(v->guardAll(activity,paths,true));Button close=new Button(activity);close.setText(UiText.get(R.string.ui_back));shield.addView(close);close.setOnClickListener(v->activity.finish());root.addView(shield,new android.view.ViewGroup.LayoutParams(-1,-1));}
            if(prompt)ensureDocument(activity,locked,()->guardAll(activity,paths,true));
        }else{content.setVisibility(android.view.View.VISIBLE);if(existing!=null)root.removeView(existing);}
    }
    static void authenticate(AppCompatActivity a,long id,Runnable done){
        if(prefs(a).getBoolean("bio_"+id,false)&&BiometricManager.from(a).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)==BiometricManager.BIOMETRIC_SUCCESS){
            BiometricPrompt prompt=new BiometricPrompt(a,ContextCompat.getMainExecutor(a),new BiometricPrompt.AuthenticationCallback(){
                @Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result){unlocked(id);done.run();}
                @Override public void onAuthenticationError(int code,CharSequence message){authenticationError(a,id,done,code);}
            });prompt.authenticate(new BiometricPrompt.PromptInfo.Builder().setTitle(UiText.get(R.string.ui_unlock_folder)).setSubtitle("Use an enrolled device biometric").setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG).setNegativeButtonText("Use folder PIN").build());
        }else askPin(a,id,done);
    }
    static void authenticationError(AppCompatActivity a,long id,Runnable done,int code){
        if(code==BiometricPrompt.ERROR_USER_CANCELED||code==BiometricPrompt.ERROR_CANCELED)return;
        // Hardware errors and biometric lockout must still allow the folder PIN.
        a.getWindow().getDecorView().post(()->{if(!a.isFinishing()&&!a.isDestroyed())askPin(a,id,done);});
    }
    private static void askPin(AppCompatActivity a,long id,Runnable done){
        EditText input=new EditText(a);input.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_VARIATION_PASSWORD);input.setSingleLine();
        AlertDialog dialog=new AlertDialog.Builder(a).setTitle(UiText.get(R.string.ui_folder_pin)).setView(input).setNegativeButton(UiText.get(R.string.ui_cancel),null).setPositiveButton(UiText.get(R.string.ui_unlock),null).create();
        dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(v->{long wait=prefs(a).getLong("wait_"+id,0)-System.currentTimeMillis();if(wait>0){input.setError(UiText.format(R.string.ui_try_again_in_value_seconds, (wait/1000+1)));return;}String pin=input.getText().toString();dialog.getButton(-1).setEnabled(false);
            new Thread(()->{boolean valid=verify(pin,prefs(a).getString("pin_"+id,""));a.runOnUiThread(()->{if(a.isDestroyed()||!dialog.isShowing())return;dialog.getButton(-1).setEnabled(true);if(valid){prefs(a).edit().putInt("fails_"+id,0).remove("wait_"+id).apply();unlocked(id);dialog.dismiss();done.run();}else{int failures=prefs(a).getInt("fails_"+id,0)+1;prefs(a).edit().putInt("fails_"+id,failures).putLong("wait_"+id,failures%5==0?System.currentTimeMillis()+30000:0).apply();input.setError(UiText.get(R.string.ui_incorrect_pin));input.setText("");}});},"folder-auth").start();}));dialog.show();
    }
    static void configure(AppCompatActivity a,long id,Runnable refresh){
        try(DocumentStore store=new DocumentStore(a)){for(Long root:ancestors(a,store,id))if(root!=id&&sessions.getOrDefault(root,0L)<SystemClock.elapsedRealtime()){authenticate(a,root,()->configure(a,id,refresh));return;}}
        if(protectedFolder(a,id)){authenticate(a,id,()->new AlertDialog.Builder(a).setTitle(UiText.get(R.string.ui_folder_protection)).setItems(new String[]{UiText.get(R.string.ui_change_pin), prefs(a).getBoolean("bio_"+id,false) ? UiText.get(R.string.ui_disable_biometrics) : UiText.get(R.string.ui_enable_biometrics), UiText.get(R.string.ui_remove_protection), UiText.get(R.string.ui_lock_all_folders_now)},(d,w)->{if(w==0)newPin(a,id,refresh);else if(w==1){if(!prefs(a).getBoolean("bio_"+id,false)&&BiometricManager.from(a).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)!=BiometricManager.BIOMETRIC_SUCCESS){Toast.makeText(a,UiText.get(R.string.ui_set_up_a_strong_device_biometric_in_android_settings_first),Toast.LENGTH_LONG).show();return;}prefs(a).edit().putBoolean("bio_"+id,!prefs(a).getBoolean("bio_"+id,false)).apply();refresh.run();}else if(w==2){prefs(a).edit().remove("pin_"+id).remove("bio_"+id).apply();sessions.remove(id);refresh.run();}else{lockAll();refresh.run();}}).show());}else newPin(a,id,refresh);
    }
    private static void newPin(AppCompatActivity a,long id,Runnable refresh){LinearLayout fields=new LinearLayout(a);fields.setOrientation(android.widget.LinearLayout.VERTICAL);EditText pin=new EditText(a),confirm=new EditText(a);pin.setHint(UiText.get(R.string.ui_pin_at_least_6_digits));confirm.setHint(UiText.get(R.string.ui_repeat_pin));for(EditText input:new EditText[]{pin,confirm}){input.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_VARIATION_PASSWORD);fields.addView(input);}
        AlertDialog dialog=new AlertDialog.Builder(a).setTitle(UiText.get(R.string.ui_protect_folder)).setMessage(UiText.get(R.string.ui_protects_this_folder_and_its_subfolders_inside_paperflow_files_use_android)).setView(fields).setNegativeButton(UiText.get(R.string.ui_cancel),null).setPositiveButton(UiText.get(R.string.ui_save),null).create();dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(v->{String value=pin.getText().toString();if(!value.matches("[0-9]{6,32}")||!value.equals(confirm.getText().toString())){confirm.setError(UiText.get(R.string.ui_enter_matching_pins_with_6_32_digits));return;}dialog.getButton(-1).setEnabled(false);new Thread(()->{try{byte[] salt=new byte[16];new SecureRandom().nextBytes(salt);String encoded=android.util.Base64.encodeToString(salt,2)+":"+android.util.Base64.encodeToString(derive(value,salt),2);prefs(a).edit().putString("pin_"+id,encoded).putInt("fails_"+id,0).remove("wait_"+id).apply();unlocked(id);try(DocumentStore store=new DocumentStore(a);Cursor docs=store.db().rawQuery("SELECT id,path FROM docs",null)){while(docs.moveToNext())if(!store.protectionRoots(docs.getString(1)).isEmpty()){android.content.ContentValues clear=new android.content.ContentValues();clear.put("search_text","");clear.put("search_meta","");clear.put("indexed_mtime",0);store.db().update("docs",clear,"id=?",new String[]{docs.getString(0)});}}a.runOnUiThread(()->{if(!a.isDestroyed()){dialog.dismiss();refresh.run();}});}catch(Exception e){a.runOnUiThread(()->{dialog.getButton(-1).setEnabled(true);confirm.setError(UiText.get(R.string.ui_could_not_save_folder_protection));});}},"folder-pin-setup").start();}));dialog.show();}
    private static byte[] derive(String pin,byte[] salt)throws Exception{PBEKeySpec spec=new PBEKeySpec(pin.toCharArray(),salt,120000,256);try{return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();}finally{spec.clearPassword();}}
    private static boolean verify(String pin,String encoded){try{String[] fields=encoded.split(":");return fields.length==2&&MessageDigest.isEqual(derive(pin,android.util.Base64.decode(fields[0],2)),android.util.Base64.decode(fields[1],2));}catch(Exception e){return false;}}
}
