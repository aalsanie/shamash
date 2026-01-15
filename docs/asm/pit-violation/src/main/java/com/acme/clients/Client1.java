package com.acme.clients;

import com.acme.core.TargetHub;

public class Client1 {
    private final TargetHub hub = new TargetHub();

    public String use() {
        return "1-" + hub.id();
    }
}
