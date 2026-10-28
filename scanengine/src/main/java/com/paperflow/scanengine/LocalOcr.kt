package com.paperflow.scanengine

import android.content.Context
import android.graphics.Bitmap
import com.paperflow.imageprocessing.ImageRect
import com.paperflow.imageprocessing.OcrTextBox
import com.paperflow.translation.OfflineRuntime

/** PaddleOCR/MNN, shared by scan exports and all PDF tools. */
object LocalOcr {
    @JvmStatic fun activeLanguages(context: Context): String {
        val rows=OfflineRuntime.get(context).languages()
        val selected=context.getSharedPreferences("local-languages",0).getStringSet("ocr-enabled",emptySet())!!
        return (0 until rows.length()).map { rows.getJSONObject(it) }
            .filter { it.optBoolean("ocr") && selected.contains(it.getString("code")) }
            .map { it.getString("code") }.sorted().joinToString("+")
    }
    @JvmStatic fun recognize(context: Context, bitmap: Bitmap): List<OcrTextBox> =
        recognize(context,bitmap,activeLanguages(context),50f)
    /** Enabled script recognizers share the upstream detector and word coordinates. */
    @JvmStatic @Synchronized fun recognize(context: Context, bitmap: Bitmap, languages: String, minimumConfidence: Float): List<OcrTextBox> {
        if(languages.isBlank())return emptyList()
        val words=OfflineRuntime.get(context).recognize(bitmap,languages,minimumConfidence).getJSONArray("words")
        return (0 until words.length()).map { i ->
            val word=words.getJSONObject(i)
            OcrTextBox(word.getString("text"),ImageRect(word.getInt("left"),word.getInt("top"),word.getInt("right"),word.getInt("bottom")),word.getInt("lineHeight"),word.getInt("baseline"))
        }
    }
    @JvmStatic fun text(context: Context,bitmap: Bitmap): String = recognize(context,bitmap).joinToString(" ") { it.text }
}
