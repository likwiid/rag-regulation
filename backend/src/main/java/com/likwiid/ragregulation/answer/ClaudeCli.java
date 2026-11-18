package com.likwiid.ragregulation.answer;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.likwiid.ragregulation.answer.ClaudeClient.ModelRefusedException;
import com.likwiid.ragregulation.answer.ClaudeClient.Result;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Development backend: runs the Claude Code command line in print mode instead of calling the API.
 * It uses whatever login the local {@code claude} command has, so no API key is needed. The request is
 * the same as on the API path (system prompt, one user message, a JSON schema for the reply), with all
 * tools, settings and MCP servers switched off so the model only answers.
 */
final class ClaudeCli {

    private static final long TIMEOUT_MINUTES = 10;

    private final ObjectMapper json = new ObjectMapper();

    <T> Result<T> ask(String model, String system, String user, Class<T> type) {
        try {
            Path work = Files.createTempDirectory("rag-claude-cli");
            Path stdout = work.resolve("out.json");
            Path stderr = work.resolve("err.txt");
            ProcessBuilder builder = new ProcessBuilder("claude", "-p",
                    "--model", model,
                    "--system-prompt", system,
                    "--tools", "",
                    "--strict-mcp-config",
                    "--setting-sources", "",
                    "--no-session-persistence",
                    "--output-format", "json",
                    "--json-schema", json.writeValueAsString(schema(type)));
            builder.directory(work.toFile());
            builder.redirectOutput(stdout.toFile());
            builder.redirectError(stderr.toFile());
            // A parent Claude Code session marks its children; a clean environment makes this a normal run.
            builder.environment().remove("CLAUDECODE");
            builder.environment().remove("CLAUDE_CODE_ENTRYPOINT");
            Process process = builder.start();
            try (OutputStream stdin = process.getOutputStream()) {
                stdin.write(user.getBytes(StandardCharsets.UTF_8));
            }
            if (!process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new IllegalStateException("The claude command did not finish in " + TIMEOUT_MINUTES + " minutes");
            }
            String out = Files.readString(stdout);
            if (out.isBlank()) {
                throw new IllegalStateException("The claude command printed nothing: " + Files.readString(stderr).strip());
            }
            JsonNode reply = json.readTree(out);
            JsonNode structured = reply.get("structured_output");
            if (reply.path("is_error").asBoolean(false) || structured == null || structured.isNull()) {
                String detail = reply.path("result").asString("");
                if ("refusal".equals(reply.path("stop_reason").asString(""))) {
                    throw new ModelRefusedException("The model declined this request");
                }
                throw new IllegalStateException("The claude command returned no structured reply: " + detail);
            }
            // The model that actually ran is the key of modelUsage, not what the reply text may claim.
            String ran = model;
            for (String name : reply.path("modelUsage").propertyNames()) {
                ran = name;
            }
            JsonNode usage = reply.path("usage");
            long in = usage.path("input_tokens").asLong(0) + usage.path("cache_read_input_tokens").asLong(0)
                    + usage.path("cache_creation_input_tokens").asLong(0);
            return new Result<>(json.treeToValue(structured, type), ran, in, usage.path("output_tokens").asLong(0));
        } catch (IOException e) {
            throw new IllegalStateException("Could not run the claude command. Is Claude Code installed and logged in?", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the claude command", e);
        }
    }

    /** JSON schema for a record made of booleans, numbers, strings, lists and nested records. */
    static Map<String, Object> schema(Type type) {
        if (type == boolean.class || type == Boolean.class) {
            return Map.of("type", "boolean");
        }
        if (type == int.class || type == Integer.class || type == long.class || type == Long.class) {
            return Map.of("type", "integer");
        }
        if (type == String.class) {
            return Map.of("type", "string");
        }
        if (type instanceof ParameterizedType list && list.getRawType() == List.class) {
            return Map.of("type", "array", "items", schema(list.getActualTypeArguments()[0]));
        }
        if (type instanceof Class<?> record && record.isRecord()) {
            Map<String, Object> properties = new LinkedHashMap<>();
            List<String> required = new ArrayList<>();
            for (RecordComponent component : record.getRecordComponents()) {
                Map<String, Object> property = new LinkedHashMap<>(schema(component.getGenericType()));
                JsonPropertyDescription description = component.getAccessor().getAnnotation(JsonPropertyDescription.class);
                if (description != null) {
                    property.put("description", description.value());
                }
                properties.put(component.getName(), property);
                required.add(component.getName());
            }
            Map<String, Object> object = new LinkedHashMap<>();
            object.put("type", "object");
            object.put("properties", properties);
            object.put("required", required);
            object.put("additionalProperties", false);
            return object;
        }
        throw new IllegalArgumentException("No schema mapping for " + type);
    }
}
