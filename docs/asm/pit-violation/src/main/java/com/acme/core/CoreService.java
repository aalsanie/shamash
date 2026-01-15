package com.acme.core;

import com.acme.infra.InfraRepo;
import com.acme.annotations.InternalApi;
import com.google.common.collect.ImmutableList;
import org.apache.commons.lang3.StringUtils;

@InternalApi(reason = "Used to violate ApiForbiddenAnnotationUsageSpec")
public class CoreService {

    // multiple fields to violate maxFieldsPerClass
    private final InfraRepo repo = new InfraRepo();
    private final String a = "a";
    private final String b = "b";
    private final String c = "c";

    // many methods to violate maxMethodsPerClass
    public String m1() { return repo.fetch(); }
    public String m2() { return repo.fetch(); }
    public String m3() { return repo.fetch(); }
    public String m4() { return repo.fetch(); }
    public String m5() { return repo.fetch(); }

    // fan-out: references many packages/types
    public String fanOut() {
        var list = ImmutableList.of(a,b,c);
        return StringUtils.join(list, ",");
    }
}
