package com.this.will.never.match.com.pit.app.controller;

import com.pit.app.data.UserRepository;

// The configured root-package mismatch is intentional.
public class AdminController {
    private final UserRepository repo;
    private int unusedCount = 0; // deadcode: unused private field

    public AdminController(UserRepository repo) {
        this.repo = repo;
    }

    public String doIt() { return repo.findById("2"); }
    private String unusedSecret() { return "secret"; }
}
