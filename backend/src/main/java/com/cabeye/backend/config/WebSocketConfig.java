package com.cabeye.backend.config;

import com.cabeye.backend.websocket.RideWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/**
 * Registers the single raw WebSocket endpoint.
 *
 * <p>STOMP is deliberately not used. The brief allows raw sockets, and STOMP would add a
 * framing layer, a broker abstraction and a client library on the Android side for a
 * system that has exactly one topic shape (a ride). Raw JSON over one endpoint keeps the
 * Android client to a single OkHttp {@code WebSocketListener}.
 *
 * <p>There is one topic per ride, addressed by the {@code rideId} query parameter rather
 * than by path, so the endpoint pattern stays constant and no path templating is needed on
 * the client.
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final RideWebSocketHandler rideWebSocketHandler;

    public WebSocketConfig(RideWebSocketHandler rideWebSocketHandler) {
        this.rideWebSocketHandler = rideWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(rideWebSocketHandler, "/ws/ride")
                // Wide open. There is no auth in this MVP, and the client is a native
                // Android app plus a local browser test page — neither of which benefits
                // from an origin whitelist here. Lock this down before any public deploy.
                .setAllowedOriginPatterns("*");
    }

    /**
     * Raises the default WebSocket buffer and idle limits.
     *
     * <p>The defaults are small (8 KB) and, more importantly, the default idle timeout will
     * drop a connection during a quiet {@code finding} phase. A silent disconnect is the
     * worst possible failure for this product: the rider would be left holding a phone that
     * has stopped talking, with no way to see why.
     */
    @org.springframework.context.annotation.Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        // Under MockMvc tests there is no real server, so the servlet context has no WebSocket
        // container and the stock bean fails with "A ServletContext is required ...", taking
        // the whole test context down. Apply the limits only when a real container exists.
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean() {
            private jakarta.servlet.ServletContext servletContext;

            @Override
            public void setServletContext(jakarta.servlet.ServletContext servletContext) {
                this.servletContext = servletContext;
                super.setServletContext(servletContext);
            }

            @Override
            public void afterPropertiesSet() {
                if (servletContext != null
                        && servletContext.getAttribute(jakarta.websocket.server.ServerContainer.class.getName()) != null) {
                    super.afterPropertiesSet();
                }
            }
        };
        container.setMaxTextMessageBufferSize(64 * 1024);
        container.setMaxBinaryMessageBufferSize(64 * 1024);
        container.setMaxSessionIdleTimeout(10L * 60L * 1000L); // 10 minutes
        return container;
    }
}
