/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.asm.core;

import io.shamash.asm.core.config.ConfigValidation;
import io.shamash.asm.core.config.schema.v1.model.ShamashAsmConfigV1;
import io.shamash.asm.core.config.validation.v1.RuleSpec;
import io.shamash.asm.core.engine.EngineResult;
import io.shamash.asm.core.engine.ShamashAsmEngine;
import io.shamash.asm.core.engine.rules.DefaultRuleRegistry;
import io.shamash.asm.core.engine.rules.Rule;
import io.shamash.asm.core.engine.rules.RuleRegistry;
import io.shamash.asm.core.facts.query.FactIndex;
import io.shamash.asm.core.scan.ScanOptions;
import io.shamash.asm.core.scan.ScanResult;
import io.shamash.asm.core.scan.ShamashAsmScanRunner;
import java.io.Reader;
import java.nio.file.Path;
import java.util.List;

public final class JavaApiConsumer {
    private JavaApiConsumer() {}

    public static RuleRegistry registry(List<Rule> rules, List<RuleSpec> specs) {
        return DefaultRuleRegistry.create(rules, specs);
    }

    public static ConfigValidation.Result validate(Reader reader, RuleRegistry registry) {
        return ConfigValidation.INSTANCE.loadAndValidateV1(reader, registry);
    }

    public static ScanResult run(Path project, RuleRegistry registry) {
        return new ShamashAsmScanRunner(new ShamashAsmEngine(registry)).run(new ScanOptions(project));
    }

    public static EngineResult analyze(Path project, ShamashAsmConfigV1 config, FactIndex facts) {
        return new ShamashAsmEngine().analyze(project, "Java consumer", config, facts);
    }

    public static RuleRegistry legacyRegistry() {
        RuleRegistry delegate = DefaultRuleRegistry.Companion.create(List.of(), false);
        return new RuleRegistry() {
            @Override
            public List<Rule> all() {
                return delegate.all();
            }

            @Override
            public Rule byId(String id) {
                return delegate.byId(id);
            }
        };
    }
}
