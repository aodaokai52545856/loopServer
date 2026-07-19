package com.company.loopengine.project;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
public class ProjectProfileService {
    private static final Set<String> SHELL_INTERPRETERS = Set.of(
        "sh", "bash", "cmd", "powershell", "pwsh");
    private static final Set<String> ALLOWED_PROGRAMS = Set.of(
        "mvn", "npm", "pnpm", "yarn", "npx", "node", "java", "javac",
        "go", "gradle", "gradlew", "./gradlew", "make", "cmake",
        "dotnet", "python", "python3");
    private static final Pattern ABSOLUTE_WINDOWS = Pattern.compile("^[A-Za-z]:[\\\\/]");

    private final ProjectProfileRepository repository;
    private final JdbcClient jdbc;
    private final JsonMapper jsonMapper;
    private final JsonNode schema;

    public ProjectProfileService(
            ProjectProfileRepository repository, JdbcClient jdbc, JsonMapper jsonMapper) {
        this.repository = repository;
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
        this.schema = loadSchema(jsonMapper);
    }

    public long publish(String projectKey, JsonNode config, String publishedBy) {
        List<Violation> violations = validate(config);
        if (!violations.isEmpty()) {
            throw new ValidationException(violations);
        }

        String repositoryPath = config.path("repository").asString();
        UUID profileId = findProfileId(projectKey)
            .orElseGet(() -> repository.create(projectKey, repositoryPath));
        return repository.publish(profileId, config, publishedBy);
    }

    List<Violation> validate(JsonNode config) {
        List<Violation> violations = new ArrayList<>();
        validateAgainstSchema(config, schema, "", violations);
        validateServerRules(config, violations);
        return violations;
    }

    private void validateServerRules(JsonNode config, List<Violation> violations) {
        validatePathArray(config.get("modules"), "modules", violations);
        validatePathArray(config.get("contextPaths"), "contextPaths", violations);
        validatePathArray(config.get("forbiddenPaths"), "forbiddenPaths", violations);

        JsonNode commands = config.get("validationCommands");
        if (commands != null && commands.isArray()) {
            for (int i = 0; i < commands.size(); i++) {
                JsonNode command = commands.get(i);
                String programPath = "validationCommands/" + i + "/program";
                if (command != null && command.path("program").isString()) {
                    String program = command.path("program").asString();
                    String baseName = programBaseName(program);
                    if (SHELL_INTERPRETERS.contains(baseName)) {
                        violations.add(new Violation(
                            programPath, "shell interpreter is not allowed: " + program));
                    } else if (!ALLOWED_PROGRAMS.contains(baseName)
                            && !ALLOWED_PROGRAMS.contains(program)) {
                        violations.add(new Violation(
                            programPath, "program is outside administrator allowlist: " + program));
                    }
                }
                JsonNode args = command == null ? null : command.get("args");
                if (args != null && args.isArray()) {
                    for (int j = 0; j < args.size(); j++) {
                        if (args.get(j).isString()) {
                            rejectUnsafePath(
                                args.get(j).asString(),
                                "validationCommands/" + i + "/args/" + j,
                                violations);
                        }
                    }
                }
            }
        }
    }

    private void validatePathArray(JsonNode array, String field, List<Violation> violations) {
        if (array == null || !array.isArray()) {
            return;
        }
        for (int i = 0; i < array.size(); i++) {
            if (array.get(i).isString()) {
                rejectUnsafePath(array.get(i).asString(), field + "/" + i, violations);
            }
        }
    }

    private static void rejectUnsafePath(String value, String path, List<Violation> violations) {
        if (value.indexOf('\0') >= 0) {
            violations.add(new Violation(path, "contains NUL"));
            return;
        }
        if (value.startsWith("/") || ABSOLUTE_WINDOWS.matcher(value).find() || value.startsWith("\\\\")) {
            violations.add(new Violation(path, "absolute path is not allowed"));
            return;
        }
        String normalized = value.replace('\\', '/');
        for (String segment : normalized.split("/")) {
            if ("..".equals(segment)) {
                violations.add(new Violation(path, "path escapes repository"));
                return;
            }
        }
    }

    private static String programBaseName(String program) {
        String normalized = program.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        String name = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        return name.toLowerCase(Locale.ROOT);
    }

    private void validateAgainstSchema(
            JsonNode instance, JsonNode schemaNode, String path, List<Violation> violations) {
        if (schemaNode == null || schemaNode.isNull()) {
            return;
        }
        if (schemaNode.has("type")) {
            if (!matchesType(instance, schemaNode.get("type").asString())) {
                violations.add(new Violation(pathOrRoot(path), "type must be " + schemaNode.get("type").asString()));
                return;
            }
        }
        if (instance == null || instance.isNull()) {
            return;
        }
        if (schemaNode.has("enum") && schemaNode.get("enum").isArray()) {
            boolean matched = false;
            for (JsonNode option : schemaNode.get("enum")) {
                if (instance.equals(option)) {
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                violations.add(new Violation(pathOrRoot(path), "value is not in enum"));
            }
        }
        if (schemaNode.has("const") && !instance.equals(schemaNode.get("const"))) {
            violations.add(new Violation(pathOrRoot(path), "value must equal const"));
        }
        if (instance.isString()) {
            String text = instance.asString();
            if (schemaNode.has("minLength") && text.length() < schemaNode.get("minLength").asInt()) {
                violations.add(new Violation(pathOrRoot(path), "shorter than minLength"));
            }
            if (schemaNode.has("pattern")) {
                Pattern pattern = Pattern.compile(schemaNode.get("pattern").asString());
                if (!pattern.matcher(text).matches()) {
                    violations.add(new Violation(pathOrRoot(path), "does not match pattern"));
                }
            }
        }
        if (instance.isNumber()) {
            double value = instance.asDouble();
            if (schemaNode.has("minimum") && value < schemaNode.get("minimum").asDouble()) {
                violations.add(new Violation(pathOrRoot(path), "less than minimum"));
            }
            if (schemaNode.has("maximum") && value > schemaNode.get("maximum").asDouble()) {
                violations.add(new Violation(pathOrRoot(path), "greater than maximum"));
            }
        }
        if (instance.isArray()) {
            if (schemaNode.has("minItems") && instance.size() < schemaNode.get("minItems").asInt()) {
                violations.add(new Violation(pathOrRoot(path), "fewer items than minItems"));
            }
            JsonNode itemSchema = schemaNode.get("items");
            if (itemSchema != null) {
                for (int i = 0; i < instance.size(); i++) {
                    validateAgainstSchema(instance.get(i), itemSchema, join(path, String.valueOf(i)), violations);
                }
            }
        }
        if (instance.isObject()) {
            if (schemaNode.has("required") && schemaNode.get("required").isArray()) {
                for (JsonNode required : schemaNode.get("required")) {
                    String field = required.asString();
                    if (!instance.has(field) || instance.get(field).isNull()) {
                        violations.add(new Violation(join(path, field), "required property missing"));
                    }
                }
            }
            JsonNode properties = schemaNode.get("properties");
            boolean additionalForbidden = schemaNode.path("additionalProperties").isBoolean()
                && !schemaNode.path("additionalProperties").asBoolean();
            JsonNode additionalSchema = schemaNode.get("additionalProperties");
            Iterator<String> names = instance.propertyNames().iterator();
            while (names.hasNext()) {
                String name = names.next();
                JsonNode child = instance.get(name);
                if (properties != null && properties.has(name)) {
                    validateAgainstSchema(child, properties.get(name), join(path, name), violations);
                } else if (additionalForbidden) {
                    violations.add(new Violation(join(path, name), "additional property not allowed"));
                } else if (additionalSchema != null && additionalSchema.isObject()) {
                    validateAgainstSchema(child, additionalSchema, join(path, name), violations);
                }
            }
        }
    }

    private static boolean matchesType(JsonNode instance, String type) {
        return switch (type) {
            case "object" -> instance != null && instance.isObject();
            case "array" -> instance != null && instance.isArray();
            case "string" -> instance != null && instance.isString();
            case "integer" -> instance != null && instance.isIntegralNumber();
            case "number" -> instance != null && instance.isNumber();
            case "boolean" -> instance != null && instance.isBoolean();
            case "null" -> instance == null || instance.isNull();
            default -> true;
        };
    }

    private static String join(String path, String child) {
        return path.isEmpty() ? child : path + "/" + child;
    }

    private static String pathOrRoot(String path) {
        return path.isEmpty() ? "$" : path;
    }

    private Optional<UUID> findProfileId(String projectKey) {
        return jdbc.sql("select id from project_profile where project_key = :projectKey")
            .param("projectKey", projectKey)
            .query(UUID.class)
            .optional();
    }

    private static JsonNode loadSchema(JsonMapper jsonMapper) {
        List<Path> candidates = List.of(
            Path.of("contracts/v1/project-profile.schema.json"),
            Path.of("../contracts/v1/project-profile.schema.json"),
            Path.of(System.getProperty("basedir", "."))
                .resolve("../contracts/v1/project-profile.schema.json")
                .normalize());
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                try {
                    return jsonMapper.readTree(Files.readString(candidate));
                } catch (IOException ex) {
                    throw new IllegalStateException("Unable to read project profile schema", ex);
                }
            }
        }
        throw new IllegalStateException("project-profile.schema.json not found");
    }

    public record Violation(String path, String message) {
        Map<String, String> asMap() {
            Map<String, String> map = new LinkedHashMap<>();
            map.put("path", path);
            map.put("message", message);
            return map;
        }
    }

    public static final class ValidationException extends RuntimeException {
        private final List<Violation> violations;

        ValidationException(List<Violation> violations) {
            super(violations.stream()
                .map(v -> v.path() + ": " + v.message())
                .collect(Collectors.joining("; ")));
            this.violations = List.copyOf(violations);
        }

        public List<Map<String, String>> violations() {
            return violations.stream().map(Violation::asMap).toList();
        }
    }
}
