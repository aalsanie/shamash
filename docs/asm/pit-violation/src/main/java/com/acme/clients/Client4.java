package com.acme.clients;

import com.acme.core.TargetHub;

public class Client4 {
    private final TargetHub hub = new TargetHub();

    public String use() {
        return "4-" + hub.id();
    }
}
