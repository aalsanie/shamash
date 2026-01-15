package com.acme.clients;

import com.acme.core.TargetHub;

public class Client6 {
    private final TargetHub hub = new TargetHub();

    public String use() {
        return "6-" + hub.id();
    }
}
