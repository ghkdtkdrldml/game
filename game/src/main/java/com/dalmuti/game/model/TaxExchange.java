package com.dalmuti.game.model;

import lombok.Getter;
import java.util.List;

// 한 쌍의 세금 교환 기록 (농노→달무티, 소작농→총리대신)
@Getter
public class TaxExchange {
    private final String payerId;
    private final String receiverId;
    // 하위 신분이 자동으로 낸 카드
    private final List<CardType> paidCards;
    // 상위 신분이 골라서 돌려준 카드. null이면 반환 대기 중
    private List<CardType> returnedCards;

    public TaxExchange(String payerId, String receiverId, List<CardType> paidCards) {
        this.payerId = payerId;
        this.receiverId = receiverId;
        this.paidCards = List.copyOf(paidCards);
    }

    public boolean isPending() {
        return returnedCards == null;
    }

    void complete(List<CardType> returnedCards) {
        this.returnedCards = List.copyOf(returnedCards);
    }
}
