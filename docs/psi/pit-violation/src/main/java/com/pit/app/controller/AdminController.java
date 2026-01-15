package com.this.will.never.match.com.pit.app.controller;

import com.pit.app.data.UserRepository;

// Role: controller (package .controller) OK, but packages.rootPackage rule will still fail (we set mismatch)
public class AdminController {
    private final UserRepository repo;
    private int unusedCount = 0; // deadcode: unused private field

    public AdminController(UserRepository repo) {
        this.repo = repo;
    }

    public String doIt() { return repo.findById("2"); }
    private String unusedSecret() { return "secret"; }
}
