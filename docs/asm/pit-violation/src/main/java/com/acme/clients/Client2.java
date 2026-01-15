package com.acme.clients;

import com.acme.core.TargetHub;

public class Client2 {
    private final TargetHub hub = new TargetHub();

    public String use() {
        return "2-" + hub.id();
    }
}
