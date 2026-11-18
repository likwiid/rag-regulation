package com.likwiid.ragregulation.answer;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.beta.messages.BetaStopReason;
import com.anthropic.models.beta.messages.MessageCreateParams;
import com.anthropic.models.beta.messages.StructuredMessage;
import com.likwiid.ragregulation.config.AppProperties;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/** Thin wrapper around the Anthropic SDK: one request in, one schema-validated object out. */
@Component
public class ClaudeClient {

    private static final String FALLBACK_BETA = "server-side-fallback-2026-07-01";

    public record Result<T>(T value, String model, long inputTokens, long outputTokens) {
    }

    /** The model declined to answer (stop reason "refusal"). */
    public static class ModelRefusedException extends RuntimeException {
        public ModelRefusedException(String message) {
            super(message);
        }
    }

    private final AppProperties properties;
    private final AtomicLong inputTokens = new AtomicLong();
    private final AtomicLong outputTokens = new AtomicLong();
    private AnthropicClient client;

    public ClaudeClient(AppProperties properties) {
        this.properties = properties;
    }

    public boolean enabled() {
        return properties.generationEnabled();
    }

    private synchronized AnthropicClient client() {
        if (!enabled()) {
            throw new IllegalStateException("ANTHROPIC_API_KEY is not set");
        }
        if (client == null) {
            client = AnthropicOkHttpClient.builder().apiKey(properties.anthropicApiKey()).build();
        }
        return client;
    }

    /**
     * Sends one system prompt and one user message and parses the reply into {@code type}. The JSON schema
     * is derived from the class and enforced by the API (structured outputs), so no parsing of free text.
     */
    public <T> Result<T> ask(String model, String system, String user, Class<T> type) {
        MessageCreateParams.Builder builder = MessageCreateParams.builder()
                .model(model)
                .maxTokens(16000L)
                .system(system)
                .addUserMessage(user);
        if (supportsServerSideFallback(model)) {
            // If a safety classifier declines the request, the API retries it on a fallback model
            // instead of returning an empty refusal.
            builder.addBeta(FALLBACK_BETA).fallbacksDefault();
        }
        StructuredMessage<T> message = client().beta().messages().create(builder.outputConfig(type).build());
        inputTokens.addAndGet(message.usage().inputTokens());
        outputTokens.addAndGet(message.usage().outputTokens());

        if (message.stopReason().filter(BetaStopReason.REFUSAL::equals).isPresent()) {
            throw new ModelRefusedException("The model declined this request");
        }
        if (message.stopReason().filter(BetaStopReason.MAX_TOKENS::equals).isPresent()) {
            throw new IllegalStateException("The reply was cut off at the output token limit");
        }
        T value = message.content().stream()
                .flatMap(block -> block.text().stream())
                .map(text -> text.text())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("The reply contained no text block"));
        return new Result<>(value, message.model().asString(), message.usage().inputTokens(),
                message.usage().outputTokens());
    }

    private static boolean supportsServerSideFallback(String model) {
        return model.startsWith("claude-opus-5") || model.startsWith("claude-fable-5")
                || model.startsWith("claude-sonnet-5-5");
    }

    public long totalInputTokens() {
        return inputTokens.get();
    }

    public long totalOutputTokens() {
        return outputTokens.get();
    }
}
