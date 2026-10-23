package com.example.myapplication;

/** Stable capture IDs, shared by the scan and live translation camera. */
final class CaptureModes {
    static final String[] ALL={"Photo","To Word","Sign","Single","Batch","ID Cards","Book","Whiteboard","Slides","Timestamp"};
    static boolean detectsPaper(String mode){return mode!=null&&!mode.equals("Photo");}
    static String label(String mode){return "Single".equals(mode)?UiText.get(R.string.ui_page_capture_mode):"Photo".equals(mode)?UiText.get(R.string.ui_photo_capture_mode):UiText.label(mode);}
    static String[] labels(){String[] labels=new String[ALL.length];for(int i=0;i<ALL.length;i++)labels[i]=label(ALL[i]);return labels;}
}
