package com.dalmuti.game.dto;

// 방 전체에 보내는 이모티콘 (누가 보냈는지 + 이모티콘)
public record EmoteEvent(String playerId, String emoji) {
}
