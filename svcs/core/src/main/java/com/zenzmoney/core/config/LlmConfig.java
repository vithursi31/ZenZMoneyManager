package com.zenzmoney.core.config;

import io.netty.channel.ChannelOption;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

/**
 * HTTP transport for the self-hosted extraction model (chat entry plan §8). Kept
 * out of {@code OllamaExtractionClient} so the client stays pure request/response
 * shaping and can be unit-tested against a stub {@link WebClient}.
 *
 * <p>Both timeouts are deliberate. A CPU-hosted model is slow and can be entirely
 * absent (its compose service is opt-in), so a call must fail fast and let the chat
 * flow degrade to a reply rather than pin a request thread: the connect timeout
 * covers "nothing is listening", the response timeout covers "the model is
 * thinking forever".
 */
@Configuration
public class LlmConfig {

    @Bean
    WebClient llmWebClient(@Value("${zenzmoney.llm.base-url}") String baseUrl,
                           @Value("${zenzmoney.llm.connect-timeout-ms}") int connectTimeoutMs,
                           @Value("${zenzmoney.llm.timeout-ms}") int timeoutMs) {

        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMs)
                .responseTimeout(Duration.ofMillis(timeoutMs));

        return WebClient.builder()
                .baseUrl(baseUrl)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }
}
