package com.zenzmoney.core.config;

import io.netty.channel.ChannelOption;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

/**
 * HTTP transport for Google's hosted model, used only when
 * {@code zenzmoney.llm.provider=gemini}. Kept beside {@link LlmConfig} rather than
 * inside it because the two have nothing in common but the shape: different host,
 * different auth, and a response timeout an order of magnitude tighter.
 */
@Configuration
@ConditionalOnProperty(name = "zenzmoney.llm.provider", havingValue = "gemini")
public class GeminiConfig {

    /**
     * The API key travels as a header, never as a {@code ?key=} query parameter.
     * A query parameter is written into access logs, the request line
     * {@code MdcContextFilter} records, and every proxy in between — which is how a
     * live credential ends up in a file kept for a year.
     */
    private static final String API_KEY_HEADER = "x-goog-api-key";

    @Bean
    WebClient geminiWebClient(@Value("${zenzmoney.gemini.base-url}") String baseUrl,
                              @Value("${zenzmoney.gemini.api-key}") String apiKey,
                              @Value("${zenzmoney.gemini.connect-timeout-ms}") int connectTimeoutMs,
                              @Value("${zenzmoney.gemini.timeout-ms}") int timeoutMs) {

        if (apiKey == null || apiKey.isBlank()) {
            // Fail the boot, not every request. An app that starts and then answers
            // "I couldn't read that" to every message looks healthy and is not — the
            // same reasoning ExtractionPrompt uses for a missing prompt file.
            throw new IllegalStateException(
                    "zenzmoney.llm.provider=gemini but zenzmoney.gemini.api-key is empty. "
                            + "Set GEMINI_API_KEY, or set LLM_PROVIDER=ollama.");
        }

        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMs)
                .responseTimeout(Duration.ofMillis(timeoutMs));

        return WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(API_KEY_HEADER, apiKey)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }
}
