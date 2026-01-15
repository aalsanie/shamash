package com.acme.b;

import com.acme.a.A;

public class B {
    private final A a = null; // cycle reference

    public String call() {
        return "B";
    }
}
