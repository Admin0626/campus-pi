package com.campuspi.member;

import com.campuspi.account.*;
import jakarta.servlet.http.*;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.*;
import org.springframework.web.servlet.config.annotation.*;

@Configuration
public class MemberAccessConfig implements WebMvcConfigurer {
    private final AccountService accounts;
    public MemberAccessConfig(AccountService accounts){this.accounts=accounts;}
    @Override public void addInterceptors(InterceptorRegistry registry){registry.addInterceptor(new HandlerInterceptor(){
        @Override public boolean preHandle(HttpServletRequest req,HttpServletResponse res,Object handler){
            if(!(handler instanceof HandlerMethod method)||!method.getBeanType().getPackageName().equals("com.campuspi.member"))return true;
            ApiSupport.requestId(req);res.setHeader("Cache-Control","no-store");
            String path=req.getRequestURI();boolean optional=req.getMethod().equals("GET")&&(path.equals("/api/v1/products")||path.matches("/api/v1/products/[1-9][0-9]*")||path.equals("/api/v1/posts")||path.matches("/api/v1/posts/[^/]+")||path.equals("/api/v1/tasks"));
            if(req.getAttribute("identity")==null&&(!optional||req.getHeader("Authorization")!=null))req.setAttribute("identity",accounts.authenticate(req.getHeader("Authorization")));
            return true;
        }
    }).addPathPatterns("/api/v1/**");}
}
