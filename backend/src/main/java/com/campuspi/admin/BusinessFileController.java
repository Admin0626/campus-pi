package com.campuspi.admin;

import com.campuspi.account.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartHttpServletRequest;

@RestController
public class BusinessFileController {
    private final BusinessFileService files;
    private final AccountService accounts;
    public BusinessFileController(BusinessFileService files,AccountService accounts){this.files=files;this.accounts=accounts;}
    private AccountService.Identity actor(HttpServletRequest r){return (AccountService.Identity)r.getAttribute("identity");}
    @PostMapping(value="/api/v1/files",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Object> upload(MultipartHttpServletRequest r,@RequestHeader(value="Idempotency-Key",required=false)String key) {
        if(r.getMultiFileMap().size()!=1||!r.getMultiFileMap().containsKey("file")||r.getFiles("file").size()!=1||r.getParameterMap().size()!=1||r.getParameterValues("purpose")==null||r.getParameterValues("purpose").length!=1)throw new ApiException(400,"仅支持purpose和一个file字段");
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(ApiSupport.envelope(files.upload(actor(r),r.getParameter("purpose"),r.getFile("file"),key),ApiSupport.requestId(r)));
    }
    @GetMapping("/api/v1/files/{id}/access") public ResponseEntity<Object> access(@PathVariable String id,HttpServletRequest r) {
        String authorization=r.getHeader("Authorization");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiSupport.envelope(files.access(id,authorization==null?null:accounts.authenticate(authorization)),ApiSupport.requestId(r)));
    }
    // Binary transport for API014's opaque temporary URLs, not another business operation.
    @GetMapping("/file-content/{id}/{token}") public ResponseEntity<InputStreamResource> content(@PathVariable String id,@PathVariable String token) {
        var file=files.content(id,token);
        String extension=switch(file.mime()){case "image/png"->"png";case "image/jpeg"->"jpg";case "image/webp"->"webp";default->"pdf";};
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.parseMediaType(file.mime())).contentLength(file.size())
                .header("X-Content-Type-Options","nosniff").header("Referrer-Policy","no-referrer").header("Cross-Origin-Resource-Policy","same-origin")
                .header("Content-Security-Policy","sandbox; default-src 'none'; frame-ancestors 'self'")
                .header("Content-Disposition","inline; filename=\""+file.fileId()+"."+extension+"\"").body(new InputStreamResource(file.stream()));
    }
}
