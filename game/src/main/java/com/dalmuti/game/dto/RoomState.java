package com.dalmuti.game.dto;

import com.dalmuti.game.model.CardType;
import com.dalmuti.game.model.GameRoom;
import com.dalmuti.game.model.Player;
import com.dalmuti.game.model.Rank;
import com.dalmuti.game.model.Revolution;

import java.util.List;
import java.util.Map;

// 방 전체에 공개되는 상태. 손패 내용은 제외하고 장수만 포함
public record RoomState(
        List<PlayerView> players,
        int currentTurnIndex,
        CardType currentTrickType,
        int currentTrickCount,
        boolean gameStarted,
        boolean gameOver,
        List<String> finishOrder,
        boolean taxPhase,
        // 세금으로 카드를 받아 아직 돌려주지 않은 플레이어 → 돌려줄 장수
        Map<String, Integer> pendingTaxReturns,
        // 누군가 혁명 여부를 결정 중 (누구인지는 공개하지 않음)
        boolean revolutionPending,
        // 이번 판에 선언된 혁명과 선언자 (없으면 null)
        Revolution revolution,
        String revolutionDeclarerId
) {
    public record PlayerView(String id, String name, Rank rank, int handCount, boolean connected) {
        static PlayerView from(Player p) {
            return new PlayerView(p.getId(), p.getName(), p.getRank(), p.getHand().size(), p.isConnected());
        }
    }

    public static RoomState from(GameRoom room) {
        return new RoomState(
                room.getPlayers().stream().map(PlayerView::from).toList(),
                room.getCurrentTurnIndex(),
                room.getCurrentTrickType(),
                room.getCurrentTrickCount(),
                room.isGameStarted(),
                room.isGameOver(),
                List.copyOf(room.getFinishOrder()),
                room.isTaxPhase(),
                Map.copyOf(room.getPendingTaxReturns()),
                room.isRevolutionPending(),
                room.getRevolution(),
                room.getRevolutionDeclarerId()
        );
    }
}
