package com.dalmuti.game.exception;

import lombok.Getter;

// 게임 규칙 위반 등 사용자에게 그대로 보여줄 수 있는 오류
// code: 클라이언트가 메시지 내용 대신 구분해서 처리해야 하는 경우에만 지정 (예: 이름 중복 → 로그인 화면으로)
@Getter
public class GameException extends RuntimeException {
    public static final String NAME_TAKEN = "NAME_TAKEN";
    public static final String KICKED = "KICKED";
    public static final String ROOM_NOT_FOUND = "ROOM_NOT_FOUND";

    private final String code;

    public GameException(String message) {
        this(null, message);
    }

    public GameException(String code, String message) {
        super(message);
        this.code = code;
    }
}
