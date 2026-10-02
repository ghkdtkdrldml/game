// config/WebSocketConfig.java
package com.dalmuti.game.config;

import com.dalmuti.game.auth.SessionPlayerHandshakeHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.web.socket.config.annotation.*;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        // /topic: 방 전체 공개 상태, /queue: 개인 메시지 (/user/queue/...)
        config.enableSimpleBroker("/topic", "/queue");
        config.setApplicationDestinationPrefixes("/app");
        // 세션별 전송 순서 보장 (공개 상태 → 손패 순서가 뒤바뀌지 않도록)
        config.setPreservePublishOrder(true);
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // 세션별 수신 순서 보장 (구독 → 입장 요청 순서대로 처리)
        registry.setPreserveReceiveOrder(true);
        // 세션 쿠키로 인증하므로 다른 출처(origin)의 페이지에서 연결하지 못하도록 허용 출처를 지정하지 않음 (같은 출처만)
        registry.addEndpoint("/ws-dalmuti")
                .setHandshakeHandler(new SessionPlayerHandshakeHandler())
                .withSockJS();
    }

    // 로그인(HTTP 세션)하지 않은 연결은 STOMP CONNECT 단계에서 거부
    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
                if (accessor != null && StompCommand.CONNECT.equals(accessor.getCommand()) && accessor.getUser() == null) {
                    throw new MessageDeliveryException("로그인이 필요합니다.");
                }
                return message;
            }
        });
    }
}
