package com.acme.a;

import com.acme.b.B;

public class A {
    private final B b = new B();

    public String call() {
        return "A->" + b.call();
    }
}
