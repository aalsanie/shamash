/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package shamash.verification;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.shamash.artifacts.contract.Finding;
import io.shamash.asm.core.config.ValidationError;
import io.shamash.asm.core.config.ValidationSeverity;
import io.shamash.asm.core.config.schema.v1.model.RuleDef;
import io.shamash.asm.core.config.schema.v1.model.RuleKey;
import io.shamash.asm.core.config.schema.v1.model.ShamashAsmConfigV1;
import io.shamash.asm.core.config.validation.v1.RuleSpec;
import io.shamash.asm.core.engine.ShamashAsmEngine;
import io.shamash.asm.core.engine.rules.DefaultRuleRegistry;
import io.shamash.asm.core.engine.rules.Rule;
import io.shamash.asm.core.engine.rules.RuleRegistry;
import io.shamash.asm.core.engine.rules.spi.AsmRuleRegistryProvider;
import io.shamash.asm.core.facts.query.FactIndex;
import io.shamash.asm.core.scan.ScanOptions;
import io.shamash.asm.core.scan.ScanResult;
import io.shamash.asm.core.scan.ShamashAsmScanRunner;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.ServiceLoader;
import java.util.concurrent.atomic.AtomicInteger;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

public final class JavaSmoke {
    private JavaSmoke() {}

    public static void main(String[] args) throws Exception {
        run(args[0], Path.of(args[1]), Path.of(args[2]));
    }

    public static void run(String version, Path repository, Path project) throws Exception {
        require(Runtime.version().feature() == 17, "Run publication consumers with JDK 17");
        verifyJar(ShamashAsmEngine.class, "shamash-asm-core", version, repository);
        verifyJar(Finding.class, "shamash-artifacts", version, repository);
        verifyJar(Class.forName("io.shamash.export.pipeline.ExportOrchestrator"), "shamash-export", version, repository);
        require(ServiceLoader.load(AsmRuleRegistryProvider.class).iterator().hasNext(), "Missing registry service descriptor");
        require(JavaSmoke.class.getClassLoader().getResource("com/intellij/openapi/project/Project.class") == null,
                "IntelliJ leaked onto the library consumer classpath");
        createBytecode(project);

        ScanResult discovery = new ShamashAsmScanRunner().run(new ScanOptions(project));
        require(discovery.isSuccess() && discovery.getClassUnits() == 1,
                "Embedded discovery/schema resources failed: " + discovery);

        AtomicInteger executions = new AtomicInteger();
        Rule rule = new Rule() {
            @Override
            public String getId() { return "custom.requiredParam"; }

            @Override
            public List<Finding> evaluate(FactIndex facts, RuleDef definition, ShamashAsmConfigV1 config) {
                executions.incrementAndGet();
                return List.of();
            }
        };
        RuleSpec spec = new RuleSpec() {
            @Override
            public RuleKey getKey() { return new RuleKey("custom", "requiredParam", null); }

            @Override
            public List<ValidationError> validate(String path, RuleDef definition, ShamashAsmConfigV1 config) {
                return "accepted".equals(definition.getParams().get("token")) ? List.of()
                        : List.of(new ValidationError(path + ".params.token", "token must be accepted", ValidationSeverity.ERROR));
            }
        };
        RuleRegistry registry = DefaultRuleRegistry.create(List.of(rule), List.of(spec));
        ShamashAsmEngine engine = new ShamashAsmEngine(registry);
        String yaml = config();
        require(engine.validateConfig(new StringReader(yaml)).getOk(), "Custom Java registry failed validation");
        require(!engine.validateConfig(new StringReader(yaml.replace("token: accepted", "token: rejected"))).getOk(),
                "Custom Java spec was ignored");
        Path configuration = project.resolve("custom.yml");
        Files.writeString(configuration, yaml);
        ScanResult result = new ShamashAsmScanRunner(engine).run(new ScanOptions(project, "Java consumer", configuration));
        require(result.isSuccess() && result.getClassUnits() == 1 && executions.get() == 1,
                "Custom Java rule failed to execute: " + result);
        require(result.getEngine() != null && result.getEngine().getExport() != null, "Runtime exporter did not execute");
        Path reports = project.resolve(".shamash/reports/asm");
        try (var paths = Files.walk(reports)) {
            Path json = paths.filter(path -> path.toString().endsWith(".json")).findFirst().orElseThrow();
            require(new ObjectMapper().readTree(json.toFile()).isObject(), "Exported JSON is invalid");
        }
        System.out.println("Published Java API, custom registry, discovery resources and JSON exporter passed.");
    }

    public static String config() throws Exception {
        try (InputStream input = JavaSmoke.class.getResourceAsStream("/consumer.yml")) {
            if (input == null) throw new IllegalStateException("Missing consumer fixture");
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    public static void createBytecode(Path project) throws Exception {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "sample/App", null, "java/lang/Object", null);
        writer.visitEnd();
        Path output = project.resolve("build/classes/java/main/sample/App.class");
        Files.createDirectories(output.getParent());
        Files.write(output, writer.toByteArray());
    }

    private static void verifyJar(Class<?> type, String module, String version, Path repository) throws Exception {
        Path actual = Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path expected = repository.resolve("io/github/aalsanie/" + module + "/" + version + "/" + module + "-" + version + ".jar");
        require(Files.isRegularFile(actual) && actual.toString().endsWith(".jar"), "Consumer loaded project classes: " + type);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        require(Arrays.equals(digest.digest(Files.readAllBytes(actual)), digest.digest(Files.readAllBytes(expected))),
                "Consumer resolved a different artifact: " + module);
        require(version.equals(type.getPackage().getImplementationVersion()), "Incorrect runtime version: " + module);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
