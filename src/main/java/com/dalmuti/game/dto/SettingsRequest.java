package com.dalmuti.game.dto;

// 방 설정 변경 요청 (방장 전용)
public record SettingsRequest(int disconnectGraceSeconds, int maxPlayers, boolean allowLateJoin) {
}
