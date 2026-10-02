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
    private List<CardType> hand = new ArrayList<>();
    private boolean connected = true;
    // 게임 중 연결이 끊긴 시각 (재접속 유예 시간 계산용)
    private Instant disconnectedAt;

    public Player(String id, String name) {
        this.id = id;
        this.name = name;
    }
}
