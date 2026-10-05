package com.dalmuti.game.model;

// 방 채팅 메시지 (방에 최근 몇 개만 보관)
public record ChatMessage(String playerId, String name, String text, long sentAt) {
}
