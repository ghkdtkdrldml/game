package com.dalmuti.game.auth;

import jakarta.servlet.http.HttpSession;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

import java.security.Principal;
import java.util.Map;

// WebSocket 핸드셰이크 시 HTTP 세션의 로그인 정보를 연결 사용자로 지정
public class SessionPlayerHandshakeHandler extends DefaultHandshakeHandler {

    @Override
    protected Principal determineUser(ServerHttpRequest request, WebSocketHandler wsHandler, Map<String, Object> attributes) {
        if (request instanceof ServletServerHttpRequest servletRequest) {
            HttpSession session = servletRequest.getServletRequest().getSession(false);
            if (session != null && session.getAttribute(PlayerPrincipal.SESSION_KEY) instanceof PlayerPrincipal player) {
                return player;
            }
        }
        // 로그인하지 않은 연결은 STOMP CONNECT 단계에서 거부됨
        return null;
    }
}
