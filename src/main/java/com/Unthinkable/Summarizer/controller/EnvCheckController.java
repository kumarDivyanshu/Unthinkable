package com.Unthinkable.Summarizer.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class EnvCheckController {

    @Value("${MAIL_USERNAME:NOT_FOUND}")
    private String mailUsername;

    @GetMapping("/env-check")
    public String check() {
        return "MAIL_USERNAME = " + mailUsername;
    }
}

