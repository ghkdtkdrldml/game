package com.dalmuti.game.dto;

import com.dalmuti.game.model.CardType;
import com.dalmuti.game.model.GameRoom;
import com.dalmuti.game.model.Player;
import com.dalmuti.game.model.Rank;
import com.dalmuti.game.model.TaxExchange;

import java.util.List;

// 본인에게만 전송되는 상태: 손패, 내가 참여한 세금 교환, 내가 혁명 여부를 결정할 차례인지
public record PrivateState(List<CardType> hand, TaxInfo tax, boolean canDecideRevolution) {

    public enum TaxRole { PAYER, RECEIVER }

    // role: 내가 낸 쪽(PAYER)인지 받은 쪽(RECEIVER)인지
    // returnedCards: 상위 신분이 돌려준 카드. null이면 반환 대기 중
    public record TaxInfo(TaxRole role, String counterpartName, Rank counterpartRank,
                          List<CardType> paidCards, List<CardType> returnedCards) {
    }

    public static PrivateState of(GameRoom room, Player me) {
        TaxInfo tax = null;
        if (room.isGameStarted()) {
            tax = room.findTaxExchange(me.getId()).map(e -> toTaxInfo(room, me, e)).orElse(null);
        }
        boolean canDecideRevolution = me.getId().equals(room.getRevolutionCandidateId());
        return new PrivateState(List.copyOf(me.getHand()), tax, canDecideRevolution);
    }

    private static TaxInfo toTaxInfo(GameRoom room, Player me, TaxExchange e) {
        boolean payer = e.getPayerId().equals(me.getId());
        String counterpartId = payer ? e.getReceiverId() : e.getPayerId();
        Player counterpart = room.getPlayers().stream()
                .filter(p -> p.getId().equals(counterpartId))
                .findFirst().orElse(null);

        return new TaxInfo(
                payer ? TaxRole.PAYER : TaxRole.RECEIVER,
                counterpart != null ? counterpart.getName() : counterpartId,
                counterpart != null ? counterpart.getRank() : null,
                e.getPaidCards(),
                e.getReturnedCards()
        );
    }
}
