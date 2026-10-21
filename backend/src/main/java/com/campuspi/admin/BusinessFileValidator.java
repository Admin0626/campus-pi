package com.campuspi.admin;

import com.campuspi.account.ApiException;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import javax.imageio.*;
import javax.imageio.stream.ImageInputStream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.*;
import org.springframework.stereotype.Component;

/** Content probing and parsing in addition to antivirus scanning. Original evidence bytes are retained. */
@Component
public class BusinessFileValidator {
    private static final Set<String> PURPOSES=Set.of("AVATAR","PRODUCT_IMAGE","VERIFICATION","TASK_PROOF","CHAT_IMAGE","AFTERSALE");
    private static final Set<String> ACTIVE_PDF=Set.of("JS","JavaScript","Launch","EmbeddedFiles","EmbeddedFile","RichMedia","XFA","SubmitForm","ImportData","GoToR","GoToE","Sound","Movie","Rendition");
    public BusinessFileValidator(){ImageIO.scanForPlugins();}
    public String inspect(Path file,String purpose,long size) {
        if(!PURPOSES.contains(purpose))throw new ApiException(400,"文件用途无效","purpose");
        if(size<1)throw new ApiException(400,"不能上传空文件","file");
        long max=purpose.equals("AVATAR")?2L*1024*1024:10L*1024*1024;
        if(size>max)throw new ApiException(413,"文件超过大小限制","file","UPLOAD_TOO_LARGE");
        try {
            byte[] head;
            try(InputStream in=Files.newInputStream(file)){head=in.readNBytes(12);}
            boolean pdf=head.length>=5&&new String(head,0,5,java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF-");
            if(pdf){
                if(!Set.of("VERIFICATION","TASK_PROOF","AFTERSALE").contains(purpose))throw new ApiException(415,"此用途不支持PDF");
                try(var doc=Loader.loadPDF(file.toFile())) {
                    if(doc.isEncrypted()||doc.getNumberOfPages()<1)throw new ApiException(415,"PDF需要包含可读取页面，且不能加密");
                    var seen=Collections.newSetFromMap(new IdentityHashMap<COSBase,Boolean>());
                    scanPdf(doc.getDocumentCatalog().getCOSObject(),seen);
                }
                return "application/pdf";
            }
            if(size>(purpose.equals("AVATAR")?2L:5L)*1024*1024)throw new ApiException(413,"图片超过大小限制","file","UPLOAD_TOO_LARGE");
            try(ImageInputStream input=ImageIO.createImageInputStream(file.toFile())) {
                var readers=ImageIO.getImageReaders(input);
                if(!readers.hasNext())throw new ApiException(415,"只支持实际PNG、JPEG、WebP图片或规定用途的PDF");
                ImageReader reader=readers.next();
                try {
                    String format=reader.getFormatName().toLowerCase(Locale.ROOT);
                    String mime=switch(format){case "png"->"image/png";case "jpg","jpeg"->"image/jpeg";case "webp"->"image/webp";default->throw new ApiException(415,"不支持此文件类型");};
                    reader.setInput(input,true,true);
                    int width=reader.getWidth(0),height=reader.getHeight(0);
                    if(width<1||height<1||(long)width*height>16_000_000)throw new ApiException(413,"图片解码尺寸超过安全上限");
                    if(reader.read(0)==null)throw new ApiException(415,"图片内容无法读取");
                    return mime;
                }finally{reader.dispose();}
            }
        } catch(ApiException ex){throw ex;}
        catch(Exception ex){throw new ApiException(415,"文件内容无法安全读取");}
    }
    private void scanPdf(COSBase value,Set<COSBase> seen) {
        if(value==null||!seen.add(value))return;
        if(seen.size()>100_000)throw new ApiException(415,"PDF结构过于复杂");
        if(value instanceof COSObject obj){scanPdf(obj.getObject(),seen);return;}
        if(value instanceof COSName name && ACTIVE_PDF.contains(name.getName()))throw new ApiException(415,"PDF不能包含脚本、嵌入文件或启动动作");
        if(value instanceof COSDictionary dict)for(var entry:dict.entrySet()){
            if(ACTIVE_PDF.contains(entry.getKey().getName()))throw new ApiException(415,"PDF不能包含脚本、嵌入文件或启动动作");
            scanPdf(entry.getValue(),seen);
        }
        else if(value instanceof COSArray array)for(COSBase item:array)scanPdf(item,seen);
    }
}
