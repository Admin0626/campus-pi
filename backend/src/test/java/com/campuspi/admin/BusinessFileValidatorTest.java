package com.campuspi.admin;

import com.campuspi.account.ApiException;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionJavaScript;
import org.apache.pdfbox.pdmodel.encryption.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class BusinessFileValidatorTest {
    @TempDir Path folder;
    private final BusinessFileValidator validator=new BusinessFileValidator();
    @Test void detectsPngAndJpegFromBytesNotTheClientExtension() throws Exception {
        for(String format:new String[]{"png","jpg"}){
            Path path=folder.resolve(format+".html");
            assertTrue(ImageIO.write(new BufferedImage(8,8,BufferedImage.TYPE_INT_RGB),format,path.toFile()));
            assertEquals(format.equals("png")?"image/png":"image/jpeg",validator.inspect(path,"VERIFICATION",Files.size(path)));
        }
    }
    @Test void rejectsHtmlAndSvgDisguisedAsImages() throws Exception {
        for(String text:new String[]{"<html><script>alert(1)</script></html>","<svg xmlns='http://www.w3.org/2000/svg'></svg>"}){
            Path path=folder.resolve("image.png");Files.writeString(path,text);
            assertEquals(415,assertThrows(ApiException.class,()->validator.inspect(path,"AVATAR",Files.size(path))).status);
        }
    }
    @Test void acceptsOrdinaryPdfOnlyForEvidencePurposes() throws Exception {
        Path path=folder.resolve("evidence.bin");
        try(var pdf=new PDDocument()){pdf.addPage(new PDPage());pdf.save(path.toFile());}
        assertEquals("application/pdf",validator.inspect(path,"VERIFICATION",Files.size(path)));
        assertEquals(415,assertThrows(ApiException.class,()->validator.inspect(path,"PRODUCT_IMAGE",Files.size(path))).status);
    }
    @Test void rejectsActualPdfJavaScriptActions() throws Exception {
        Path path=folder.resolve("action.pdf");
        try(var pdf=new PDDocument()){pdf.addPage(new PDPage());pdf.getDocumentCatalog().setOpenAction(new PDActionJavaScript("app.alert('test')"));pdf.save(path.toFile());}
        assertEquals(415,assertThrows(ApiException.class,()->validator.inspect(path,"VERIFICATION",Files.size(path))).status);
    }
    @Test void rejectsEncryptedPdf() throws Exception {
        Path path=folder.resolve("encrypted.pdf");
        try(var pdf=new PDDocument()){pdf.addPage(new PDPage());pdf.protect(new StandardProtectionPolicy("owner-test","user-test",new AccessPermission()));pdf.save(path.toFile());}
        assertEquals(415,assertThrows(ApiException.class,()->validator.inspect(path,"VERIFICATION",Files.size(path))).status);
    }
    @Test void scannerMissingConfigurationFailsClosed() {
        assertEquals(503,assertThrows(ApiException.class,()->new BusinessFileScanner("","").scan(folder.resolve("anything"))).status);
    }
}
