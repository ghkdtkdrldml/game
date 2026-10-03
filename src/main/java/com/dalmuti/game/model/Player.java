package com.dalmuti.game.model;

import lombok.Getter;
import lombok.Setter;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
public class Player {
    private String id;
    private String name;
    private Rank rank = Rank.CITIZEN;
    // 평민끼리의 순서 (1등 시민, 2등 시민 ...). 0이면 번호 없음 (첫 판 전, 새로 들어온 사람)
    private int citizenNo;
    private List<CardType> hand = new ArrayList<>();
    private boolean connected = true;
    // 게임 중 연결이 끊긴 시각 (재접속 유예 시간 계산용)
    private Instant disconnectedAt;
    // 입장 순서 (방장 자동 위임 시 가장 먼저 들어온 사람을 고르는 기준)
    private long joinSeq;
    // 게임 중 방장에게 강퇴됨: 이번 판은 자동 패스로 처리되고 판이 끝나면 제거
    private boolean kicked;

    public Player(String id, String name) {
        this.id = id;
        this.name = name;
    }
}
