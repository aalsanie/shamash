package com.acme.clients;

import com.acme.core.TargetHub;

public class Client5 {
    private final TargetHub hub = new TargetHub();

    public String use() {
        return "5-" + hub.id();
    }
}
