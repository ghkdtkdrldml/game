package com.dalmuti.game.model;

import lombok.Getter;

@Getter
public enum Rank {
    GREAT_DALMUTI(1, "달무티", 2),
    PRIME_MINISTER(2, "총리대신", 1),
    CITIZEN(3, "평민", 0),
    TENANT_FARMER(4, "소작농", -1),
    SERF(5, "농노", -2);

    private final int order;
    private final String title;
    private final int taxAmount;

    Rank(int order, String title, int taxAmount) {
        this.order = order;
        this.title = title;
        this.taxAmount = taxAmount;
    }
}