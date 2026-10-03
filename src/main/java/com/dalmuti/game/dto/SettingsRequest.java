package com.dalmuti.game.dto;

// 방 설정 변경 요청 (방장 전용). turnTimeLimitSeconds가 0이면 차례 제한 없음
public record SettingsRequest(int disconnectGraceSeconds, int turnTimeLimitSeconds, int maxPlayers, boolean allowLateJoin) {
}
