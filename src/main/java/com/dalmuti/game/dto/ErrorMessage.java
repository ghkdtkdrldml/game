package com.dalmuti.game.dto;

// code는 클라이언트가 특별히 처리해야 하는 오류에만 지정 (없으면 null)
public record ErrorMessage(String code, String message) {
}
