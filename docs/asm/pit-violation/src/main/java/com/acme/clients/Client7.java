package com.acme.clients;

import com.acme.core.TargetHub;

public class Client7 {
    private final TargetHub hub = new TargetHub();

    public String use() {
        return "7-" + hub.id();
    }
}
