package com.example.myapplication;

import android.content.Context;
import android.graphics.*;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;

/** CI uses generated documents, with explicit provenance; private photo audits remain optional. */
final class TestFixtures {
    static boolean ci(){return "true".equals(InstrumentationRegistry.getArguments().getString("ci"));}
    static File directory(Context context,String set)throws IOException{
        if(!ci())return new File(context.getFilesDir(),"private-fixtures/"+set);
        File root=new File(context.getFilesDir(),"ci-fixtures/"+set);
        if(!root.isDirectory()&&!root.mkdirs())throw new IOException("Cannot create CI fixtures");
        for(int variant=0;variant<3;variant++){
            File file=new File(root,"generated-document-"+variant+".png");
            Bitmap bitmap=Bitmap.createBitmap(1000,1400,Bitmap.Config.ARGB_8888);
            try{
                Canvas canvas=new Canvas(bitmap);canvas.drawColor(0xff394449);Paint paint=new Paint(3);
                canvas.save();canvas.rotate((variant-1)*4,500,700);paint.setColor(Color.WHITE);canvas.drawRect(90,100,910,1300,paint);
                paint.setColor(Color.BLACK);paint.setTextSize(44);canvas.drawText("PaperFlow generated document",120,220,paint);
                for(int y=320;y<1180;y+=85){paint.setStrokeWidth(3);canvas.drawLine(130,y,820-(y%3)*20,y,paint);}canvas.restore();
                try(OutputStream out=new FileOutputStream(file)){if(!bitmap.compress(Bitmap.CompressFormat.PNG,100,out))throw new IOException("Cannot encode fixture");}
            }finally{bitmap.recycle();}
        }
        return root;
    }
}
