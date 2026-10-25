package com.dalmuti.game.dto;

// 다음 판 준비 여부 (true: 준비 완료, false: 취소)
public record ReadyRequest(boolean ready) {
}
