package com.acme.clients;

import com.acme.core.TargetHub;

public class Client3 {
    private final TargetHub hub = new TargetHub();

    public String use() {
        return "3-" + hub.id();
    }
}
