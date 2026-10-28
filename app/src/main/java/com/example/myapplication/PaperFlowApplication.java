package com.example.myapplication;

public final class PaperFlowApplication extends android.app.Application {
    private final DeferredCleanup memoryCleanup = new DeferredCleanup(
        java.util.concurrent.Executors.newSingleThreadExecutor(r -> new Thread(r,"paperflow-cleanup")),
        CurvedPaper::release);
    @Override public void onCreate(){super.onCreate();AppTheme.initialize(this);UiText.initialize(this);PageDetectorModel.configure(this);registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks(){
        public void onActivityCreated(android.app.Activity a,android.os.Bundle b){}
        public void onActivityStarted(android.app.Activity a){}
        public void onActivityResumed(android.app.Activity a){}
        public void onActivityPaused(android.app.Activity a){FolderLock.forget(a);}
        public void onActivityStopped(android.app.Activity a){}
        public void onActivitySaveInstanceState(android.app.Activity a,android.os.Bundle b){}
        public void onActivityDestroyed(android.app.Activity a){FolderLock.forget(a);}
    });}
    @Override public void onTrimMemory(int level){super.onTrimMemory(level);if(level>=TRIM_MEMORY_RUNNING_LOW)memoryCleanup.request();}
}
