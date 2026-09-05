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
 * HTTP transport shared by the three social sign-in connectors. Kept out of the
 * connectors so each stays pure request/response shaping and can be unit-tested
 * against a stub {@link WebClient}, the same arrangement as {@link LlmConfig}.
 *
 * <p>No base URL: the three providers are different hosts, and Apple alone talks to
 * two of its own.
 *
 * <p>Both timeouts are the point of this class. Every connector blocks a request
 * thread on a provider it does not control, and sign-in is the one call a user cannot
 * route around — an untimed client pins that thread for as long as the provider is
 * willing to hang, which under any load is how the pool starves. Connect covers
 * "nothing is listening", response covers "answering, slowly, forever".
 */
@Configuration
public class OAuthHttpConfig {

    @Bean
    WebClient oauthWebClient(@Value("${zenzmoney.oauth.connect-timeout-ms}") int connectTimeoutMs,
                             @Value("${zenzmoney.oauth.timeout-ms}") int timeoutMs) {

        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMs)
                .responseTimeout(Duration.ofMillis(timeoutMs));

        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }
}
