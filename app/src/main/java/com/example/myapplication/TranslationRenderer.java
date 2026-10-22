package com.example.myapplication;

import android.graphics.*;
import android.text.*;
import com.paperflow.scanengine.Inpainter;
import com.paperflow.imageprocessing.*;
import java.util.*;

final class TranslationRenderer {
    static final class Block {
        final Rect bounds;final ArrayList<OcrTextBox> words=new ArrayList<>();String source="",translation="";float lineHeight;
        Block(OcrTextBox word){bounds=new Rect(word.getBox().getLeft(),word.getBox().getTop(),word.getBox().getRight(),word.getBox().getBottom());add(word);}
        void add(OcrTextBox word){words.add(word);bounds.union(word.getBox().getLeft(),word.getBox().getTop(),word.getBox().getRight(),word.getBox().getBottom());source+=(source.isEmpty()?"":" ")+word.getText();lineHeight=Math.max(lineHeight,word.getLineHeight());}
    }
    static final class Result {final Bitmap bitmap;final List<OcrTextBox> words;Result(Bitmap bitmap,List<OcrTextBox> words){this.bitmap=bitmap;this.words=words;}}
    static ArrayList<Block> blocks(List<OcrTextBox> input){
        return blocks(input,false);
    }
    static ArrayList<Block> blocks(List<OcrTextBox> input,boolean rightToLeft){
        ArrayList<OcrTextBox> words=new ArrayList<>(input);words.sort(Comparator.comparingInt(OcrTextBox::getLineBottom).thenComparingInt(w->rightToLeft?-w.getBox().getLeft():w.getBox().getLeft()));ArrayList<Block> lines=new ArrayList<>();
        for(OcrTextBox word:words){Block target=null;for(int i=lines.size()-1;i>=Math.max(0,lines.size()-4);i--){Block line=lines.get(i);OcrTextBox previous=line.words.get(line.words.size()-1);int gap=rightToLeft?line.bounds.left-word.getBox().getRight():word.getBox().getLeft()-line.bounds.right;if(Math.abs(previous.getLineBottom()-word.getLineBottom())<Math.max(3,word.getLineHeight()*.25)&&gap<word.getLineHeight()*3){target=line;break;}}
            if(target==null)lines.add(new Block(word));else target.add(word);
        }
        lines.sort(Comparator.comparingInt((Block b)->b.bounds.top).thenComparingInt(b->rightToLeft?-b.bounds.left:b.bounds.left));ArrayList<Block> paragraphs=new ArrayList<>();
        for(Block line:lines){Block target=null;for(int i=paragraphs.size()-1;i>=Math.max(0,paragraphs.size()-5);i--){Block block=paragraphs.get(i);float overlap=Math.max(0,Math.min(block.bounds.right,line.bounds.right)-Math.max(block.bounds.left,line.bounds.left));if(line.bounds.top>=block.bounds.bottom-line.lineHeight*.25&&line.bounds.top-block.bounds.bottom<line.lineHeight*.8&&Math.abs(rightToLeft?line.bounds.right-block.bounds.right:line.bounds.left-block.bounds.left)<line.lineHeight*1.5&&overlap>Math.min(block.bounds.width(),line.bounds.width())*.6){target=block;break;}}
            if(target==null)paragraphs.add(line);else for(OcrTextBox word:line.words)target.add(word);
        }
        return paragraphs;
    }
    static Result render(Bitmap source,List<Block> blocks){
        // Establish every layout before altering pixels; an overflow cannot produce a truncated export.
        ArrayList<StaticLayout> layouts=new ArrayList<>();TextPaint paint=new TextPaint(Paint.ANTI_ALIAS_FLAG|Paint.SUBPIXEL_TEXT_FLAG);paint.setColor(Color.BLACK);paint.setTypeface(Typeface.SANS_SERIF);
        for(int index=0;index<blocks.size();index++){Block block=blocks.get(index);if(block.translation.trim().isEmpty())throw new IllegalArgumentException(UiText.format(R.string.ui_translation_missing_in_block_value, (index+1)));StaticLayout fit=null;
            float starting=Math.max(6,block.lineHeight*.84f),minimum=Math.max(5,starting*.6f);
            for(float size=starting;size>=minimum;size-=.5f){TextPaint trial=new TextPaint(paint);trial.setTextSize(size);StaticLayout layout=StaticLayout.Builder.obtain(block.translation,0,block.translation.length(),trial,Math.max(1,block.bounds.width())).setIncludePad(false).setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_HIGH_QUALITY).build();if(layout.getHeight()<=block.bounds.height()+block.lineHeight*.1){fit=layout;break;}}
            if(fit==null)throw new IllegalArgumentException(UiText.format(R.string.ui_block_value_does_not_fit_edit_its_translation_before_exporting, (index+1)));layouts.add(fit);
        }
        Bitmap mask=Bitmap.createBitmap(source.getWidth(),source.getHeight(),Bitmap.Config.ARGB_8888);Canvas maskCanvas=new Canvas(mask);Paint brush=new Paint();brush.setColor(Color.WHITE);
        for(Block block:blocks)for(OcrTextBox word:block.words){ImageRect box=word.getBox();maskCanvas.drawRect(Math.max(0,box.getLeft()-1),Math.max(0,box.getTop()-1),Math.min(source.getWidth(),box.getRight()+1),Math.min(source.getHeight(),box.getBottom()+1),brush);}
        Bitmap cleaned;
        try{cleaned=Inpainter.inpaint(source,mask);}finally{mask.recycle();}
        Bitmap output=cleaned.isMutable()?cleaned:cleaned.copy(Bitmap.Config.ARGB_8888,true);if(output!=cleaned)cleaned.recycle();Canvas canvas=new Canvas(output);ArrayList<OcrTextBox> written=new ArrayList<>();
        for(int i=0;i<blocks.size();i++){Block block=blocks.get(i);StaticLayout layout=layouts.get(i);int save=canvas.save();canvas.translate(block.bounds.left,block.bounds.top);layout.draw(canvas);canvas.restoreToCount(save);
            for(int line=0;line<layout.getLineCount();line++){String text=block.translation.substring(layout.getLineStart(line),layout.getLineEnd(line)).trim();int top=block.bounds.top+layout.getLineTop(line),bottom=block.bounds.top+layout.getLineBottom(line);written.add(new OcrTextBox(text,new ImageRect(block.bounds.left+(int)Math.floor(layout.getLineLeft(line)),top,block.bounds.left+(int)Math.ceil(layout.getLineRight(line)),bottom),bottom-top,bottom));}
        }
        return new Result(output,written);
    }

    /** Readable fallback when the translated paragraphs cannot fit the photo's original boxes. */
    static Result readableDocument(List<Block> blocks){
        StringBuilder text=new StringBuilder();
        for(Block block:blocks){if(text.length()>0)text.append("\n\n");text.append(block.translation);}
        int width=1200,margin=64;StaticLayout layout=null;
        for(int size=32;size>=16;size-=2){
            TextPaint paint=new TextPaint(Paint.ANTI_ALIAS_FLAG|Paint.SUBPIXEL_TEXT_FLAG);paint.setColor(Color.BLACK);paint.setTextSize(size);paint.setTypeface(Typeface.SANS_SERIF);
            layout=StaticLayout.Builder.obtain(text,0,text.length(),paint,width-2*margin).setIncludePad(false).setLineSpacing(8,1).build();
            if(layout.getHeight()+2*margin<=16000)break;
        }
        if(layout==null||layout.getHeight()+2*margin>16000)throw new IllegalArgumentException(UiText.get(R.string.ui_too_much_text_in_one_photo_choose_a_smaller_section));
        Bitmap bitmap=Bitmap.createBitmap(width,Math.max(400,layout.getHeight()+2*margin),Bitmap.Config.ARGB_8888);
        Canvas canvas=new Canvas(bitmap);canvas.drawColor(Color.WHITE);canvas.translate(margin,margin);layout.draw(canvas);
        ArrayList<OcrTextBox> written=new ArrayList<>();
        for(int line=0;line<layout.getLineCount();line++){
            String value=text.substring(layout.getLineStart(line),layout.getLineEnd(line)).trim();if(value.isEmpty())continue;
            int top=margin+layout.getLineTop(line),bottom=margin+layout.getLineBottom(line);
            written.add(new OcrTextBox(value,new ImageRect(margin+(int)Math.floor(layout.getLineLeft(line)),top,margin+(int)Math.ceil(layout.getLineRight(line)),bottom),bottom-top,bottom));
        }
        return new Result(bitmap,written);
    }
}
