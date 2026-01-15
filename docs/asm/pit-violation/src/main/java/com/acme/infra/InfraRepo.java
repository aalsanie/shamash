package com.acme.infra;

import com.acme.api.PublicApi1;
import com.acme.api.PublicApi2;
import com.acme.api.PublicApi3;

public class InfraRepo implements PublicApi3 {
    public final PublicApi1 api1 = new PublicApi1();
    public final PublicApi2 api2 = new PublicApi2();

    @Override
    public String name() { return "InfraRepo"; }

    public String fetch() {
        return api1.ping() + ":" + api2.add(1,2);
    }
}
