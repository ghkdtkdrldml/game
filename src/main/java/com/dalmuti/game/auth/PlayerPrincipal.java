package com.dalmuti.game.auth;

import java.io.Serializable;
import java.security.Principal;

// 로그인 시 발급되어 HTTP 세션에 저장되고, WebSocket 연결의 사용자로도 쓰임
// getName()은 playerId를 반환 (convertAndSendToUser 대상 식별용)
public record PlayerPrincipal(String playerId, String playerName) implements Principal, Serializable {

    public static final String SESSION_KEY = "player";

    @Override
    public String getName() {
        return playerId;
    }
}
