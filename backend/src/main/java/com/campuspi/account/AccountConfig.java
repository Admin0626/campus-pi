package com.campuspi.account;

import jakarta.servlet.http.*;
import org.springframework.context.annotation.*;
import org.springframework.web.servlet.*;
import org.springframework.web.servlet.config.annotation.*;
import org.springframework.web.method.HandlerMethod;

@Configuration
public class AccountConfig implements WebMvcConfigurer {
    private final AccountService accounts;
    public AccountConfig(AccountService accounts) { this.accounts = accounts; }
    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
                ApiSupport.requestId(req);
                if (!(handler instanceof HandlerMethod method) || method.getBeanType() != AccountController.class) return true;
                res.setHeader("Cache-Control", "no-store");
                String path = req.getRequestURI();
                if (path.equals("/api/v1/auth/logout")) return true;
                if (path.startsWith("/api/v1/auth/") || path.matches("/api/v1/users/[1-9][0-9]*/profile")) return true;
                var identity = accounts.authenticate(req.getHeader("Authorization"));
                if (path.startsWith("/api/v1/admin/") && !identity.admin()) throw new ApiException(403,"需要管理员权限");
                req.setAttribute("identity",identity); return true;
            }
        }).addPathPatterns("/api/v1/**");
    }
}
