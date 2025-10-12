package com.Unthinkable.Summarizer.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HelloController {

    @GetMapping("/api/hello")
    public String hello(@AuthenticationPrincipal UserDetails user) {
        return "Hello, " + (user != null ? user.getUsername() : "anonymous");
    }
}

