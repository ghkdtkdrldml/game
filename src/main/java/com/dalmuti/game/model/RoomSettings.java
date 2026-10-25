package com.dalmuti.game.model;

import com.dalmuti.game.exception.GameException;

import java.time.Duration;

// 방장이 바꿀 수 있는 방 설정
//  disconnectGrace: 연결이 끊긴 플레이어를 기다려주는 시간 (지나면 자동 패스 등)
//  turnTimeLimit: 차례마다 주어지는 시간 (지나면 자동 패스, 선이면 선을 넘김). 0이면 제한 없음
//  maxPlayers: 최대 인원 (다음 판 대기자 포함)
//  allowLateJoin: 게임 진행 중 입장(다음 판 대기) 허용
public record RoomSettings(Duration disconnectGrace, Duration turnTimeLimit, int maxPlayers, boolean allowLateJoin) {

    // 덱(80장)과 자리 뽑기 카드(12장)로 감당 가능한 최대 인원
    public static final int MAX_PLAYERS_LIMIT = 10;
    public static final Duration MIN_GRACE = Duration.ofSeconds(15);
    public static final Duration MAX_GRACE = Duration.ofMinutes(10);
    public static final Duration MIN_TURN_LIMIT = Duration.ofSeconds(10);
    public static final Duration MAX_TURN_LIMIT = Duration.ofMinutes(5);

    public RoomSettings {
        if (disconnectGrace == null || disconnectGrace.compareTo(MIN_GRACE) < 0 || disconnectGrace.compareTo(MAX_GRACE) > 0) {
            throw new GameException("연결 대기 시간은 " + text(MIN_GRACE) + "~" + text(MAX_GRACE) + " 사이로 정해주세요.");
        }
        if (turnTimeLimit == null || (!turnTimeLimit.isZero()
                && (turnTimeLimit.compareTo(MIN_TURN_LIMIT) < 0 || turnTimeLimit.compareTo(MAX_TURN_LIMIT) > 0))) {
            throw new GameException("차례 제한 시간은 " + text(MIN_TURN_LIMIT) + "~" + text(MAX_TURN_LIMIT) + " 사이로 정하거나 제한 없음으로 해주세요.");
        }
        if (maxPlayers < GameRoom.MIN_PLAYERS || maxPlayers > MAX_PLAYERS_LIMIT) {
            throw new GameException("최대 인원은 " + GameRoom.MIN_PLAYERS + "~" + MAX_PLAYERS_LIMIT + "명 사이로 정해주세요.");
        }
    }

    // 15초, 10분처럼 표시 (분 단위로 나눠떨어지면 분)
    private static String text(Duration d) {
        return d.toSecondsPart() == 0 && d.toMinutes() > 0 ? d.toMinutes() + "분" : d.toSeconds() + "초";
    }

    public static RoomSettings defaults(Duration disconnectGrace, Duration turnTimeLimit) {
        return new RoomSettings(disconnectGrace, turnTimeLimit, MAX_PLAYERS_LIMIT, true);
    }
}
