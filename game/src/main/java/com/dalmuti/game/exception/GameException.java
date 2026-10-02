package com.dalmuti.game.exception;

// 게임 규칙 위반 등 사용자에게 그대로 보여줄 수 있는 오류
public class GameException extends RuntimeException {
    public GameException(String message) {
        super(message);
    }
}
