package com.habit.core.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // Register WebSocket handlers here as they are created
        // Example:
        // registry.addHandler(myHandler, "/ws/v1")
        //     .addInterceptors(new HttpSessionHandshakeInterceptor())
        //     .setAllowedOrigins("http://localhost:3000");
    }
}
