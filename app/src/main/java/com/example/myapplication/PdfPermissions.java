package com.example.myapplication;

import com.tom_roush.pdfbox.pdmodel.PDDocument;
import java.io.IOException;

final class PdfPermissions {
    static void modify(PDDocument doc)throws IOException{if(!doc.getCurrentAccessPermission().canModify())throw new IOException(UiText.get(R.string.ui_this_pdf_does_not_allow_changes));}
    static void extract(PDDocument doc)throws IOException{if(!doc.getCurrentAccessPermission().canExtractContent())throw new IOException(UiText.get(R.string.ui_this_pdf_does_not_allow_text_extraction));}
    static void assemble(PDDocument doc)throws IOException{if(!doc.getCurrentAccessPermission().canAssembleDocument())throw new IOException(UiText.label("This PDF does not allow page assembly"));}
    static void print(PDDocument doc)throws IOException{if(!doc.getCurrentAccessPermission().canPrint())throw new IOException(UiText.label("This PDF does not allow printing"));}
}
