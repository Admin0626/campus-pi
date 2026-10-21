package com.campuspi.admin;

import com.campuspi.account.ApiException;
import java.nio.file.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Runs a server-configured antivirus executable, never a client-provided command. */
@Component
public class BusinessFileScanner {
    private final String provider;
    private final Path executable;
    private final Semaphore capacity=new Semaphore(2);
    public BusinessFileScanner(@Value("${campus.files.scanner-provider:}") String provider,
            @Value("${campus.files.scanner-executable:}") String executable) {
        this.provider=provider;this.executable=executable.isBlank()?null:Path.of(executable).toAbsolutePath().normalize();
    }
    public String scan(Path file) {
        if(executable==null || !Files.isRegularFile(executable) || !provider.equals("DEFENDER"))throw new ApiException(503,"文件安全扫描服务尚未配置");
        if(!capacity.tryAcquire())throw new ApiException(503,"文件扫描繁忙，请稍后重试");
        Process process=null;
        try {
            // Disable remediation: a threat cannot be silently cleaned and mistaken for original clean bytes.
            process=new ProcessBuilder(executable.toString(),"-Scan","-ScanType","3","-File",file.toString(),"-DisableRemediation")
                    .redirectError(ProcessBuilder.Redirect.DISCARD).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            if(!process.waitFor(30,TimeUnit.SECONDS))throw new ApiException(503,"文件安全扫描超时");
            // Defender's nonzero status can mean a threat or an engine error. Both fail closed.
            if(process.exitValue()!=0)throw new ApiException(503,"文件未通过安全扫描或扫描服务异常");
            return provider;
        } catch(InterruptedException ex) {Thread.currentThread().interrupt();throw new ApiException(503,"文件扫描被中断");}
        catch(java.io.IOException ex){throw new ApiException(503,"文件安全扫描服务不可用");}
        finally {if(process!=null&&process.isAlive())process.destroyForcibly();capacity.release();}
    }
}
