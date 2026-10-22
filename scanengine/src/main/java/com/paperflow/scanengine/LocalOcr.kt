package com.paperflow.scanengine

import android.content.Context
import android.graphics.Bitmap
import com.googlecode.tesseract.android.TessBaseAPI
import com.googlecode.tesseract.android.TessBaseAPI.PageIteratorLevel
import com.paperflow.imageprocessing.ImageRect
import com.paperflow.imageprocessing.OcrTextBox
import java.io.File

/** Local Tesseract word/line extraction, shared by scan export, PDF OCR and translation. */
object LocalOcr {
    @JvmStatic fun activeLanguages(context: Context): String = context.getSharedPreferences("local-languages",0)
        .getStringSet("ocr-enabled",emptySet())!!.filter { File(context.filesDir,"tessdata/$it.traineddata").isFile }.sorted().joinToString("+")
    @JvmStatic @Synchronized fun recognize(context: Context, bitmap: Bitmap): List<OcrTextBox> {
        val languages=activeLanguages(context)
        if(languages.isEmpty()) return emptyList()
        return recognize(context,bitmap,languages,50f)
    }
    /** Explicit languages let a tool recognize without changing global scan OCR preferences. */
    @JvmStatic @Synchronized fun recognize(context: Context, bitmap: Bitmap, languages: String, minimumConfidence: Float): List<OcrTextBox> {
        if(languages.isEmpty()) return emptyList()
        val api=TessBaseAPI()
        try {
            check(api.init(context.filesDir.absolutePath,languages)) { "Could not initialize the selected OCR languages" }
            api.setImage(bitmap); api.utF8Text
            val result=mutableListOf<OcrTextBox>()
            val iterator=api.resultIterator ?: return result
            try {
                iterator.begin()
                do {
                    val word=iterator.getUTF8Text(PageIteratorLevel.RIL_WORD) ?: continue
                    if(word.isBlank() || iterator.confidence(PageIteratorLevel.RIL_WORD)<=minimumConfidence) continue
                    val box=iterator.getBoundingRect(PageIteratorLevel.RIL_WORD)
                    val line=iterator.getBoundingRect(PageIteratorLevel.RIL_TEXTLINE)
                    result.add(OcrTextBox(word,ImageRect(box.left,box.top,box.right,box.bottom),line.height(),line.bottom))
                } while(iterator.next(PageIteratorLevel.RIL_WORD))
            } finally { iterator.delete() }
            return result
        } finally { api.recycle() }
    }
    @JvmStatic fun text(context: Context,bitmap: Bitmap): String = recognize(context,bitmap).joinToString(" ") { it.text }
}
