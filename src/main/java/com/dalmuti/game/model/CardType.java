package com.dalmuti.game.model;

import lombok.Getter;

@Getter
public enum CardType {
    DALMUTI(1, "달무티"),
    ARCHBISHOP(2, "대주교"),
    EARL_MARSHAL(3, "시종장"),
    BARONESS(4, "남작부인"),
    ABBESS(5, "수녀원장"),
    KNIGHT(6, "기사"),
    SEAMSTRESS(7, "재봉사"),
    MASON(8, "석공"),
    COOK(9, "요리사"),
    SHEPHERDESS(10, "양치기"),
    STONECUTTER(11, "광부"),
    PEASANT(12, "농노"),
    JESTER(13, "어릿광대");

    private final int value;
    private final String korName;

    CardType(int value, String korName) {
        this.value = value;
        this.korName = korName;
    }
}