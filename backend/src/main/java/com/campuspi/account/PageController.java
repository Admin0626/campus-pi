package com.campuspi.account;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class PageController {
    // 将打包的 Vue 静态应用用于管理端及用户端的直接访问与刷新。
    @GetMapping({"/","/login","/register","/forgot-password","/home","/me","/me/profile","/me/password","/admin","/admin/users","/admin/users/{id}","/modules/{module}","/admin/modules/{module}"})
    public String app(){return "forward:/index.html";}
}
