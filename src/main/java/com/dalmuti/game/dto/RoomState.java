package com.dalmuti.game.dto;

import com.dalmuti.game.model.CardType;
import com.dalmuti.game.model.GameRoom;
import com.dalmuti.game.model.Player;
import com.dalmuti.game.model.Rank;
import com.dalmuti.game.model.Revolution;
import com.dalmuti.game.model.RoomSettings;

import java.time.Instant;
import java.util.List;
import java.util.Map;

// 방 전체에 공개되는 상태. 손패 내용은 제외하고 장수만 포함
public record RoomState(
        String code,
        String hostId,
        SettingsView settings,
        List<PlayerView> players,
        // 게임 도중 들어와 다음 판을 기다리는 사람
        List<PlayerView> waitingPlayers,
        int currentTurnIndex,
        CardType currentTrickType,
        int currentTrickCount,
        // 바닥 카드를 낸 플레이어 (바닥이 비면 null)
        String currentTrickPlayerId,
        // 바닥 카드에 섞인 어릿광대 장수 (어릿광대만 낸 경우는 0)
        int currentTrickJesterCount,
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
        String revolutionDeclarerId,
        // 자리 뽑기(첫 판 신분 정하기) 중인지, 지금까지 뽑은 카드 (playerId → 카드)
        boolean seatDrawPhase,
        Map<String, CardType> seatDraws,
        // 지금 차례의 남은 시간(ms, 제한 없거나 카드 내는 단계가 아니면 -1).
        // 기기마다 시계가 달라 절대 시각 대신 남은 시간으로 보냄
        long turnRemainingMs,
        // 시간 초과로 자동 패스된 플레이어와 누적 횟수 (알림용)
        String timedOutPlayerId,
        long timeoutCount
) {
    // kicked: 게임 중 강퇴되어 이번 판은 자동 패스, 판이 끝나면 제거됨
    // citizenNo: 평민끼리의 순서 (1등 시민 = 1). 0이면 번호 없음
    public record PlayerView(String id, String name, Rank rank, int citizenNo, int handCount, boolean connected, boolean kicked) {
        static PlayerView from(Player p) {
            return new PlayerView(p.getId(), p.getName(), p.getRank(), p.getCitizenNo(), p.getHand().size(), p.isConnected(), p.isKicked());
        }
    }

    // turnTimeLimitSeconds: 0이면 차례 제한 없음
    public record SettingsView(long disconnectGraceSeconds, long turnTimeLimitSeconds, int maxPlayers, boolean allowLateJoin) {
        static SettingsView from(RoomSettings s) {
            return new SettingsView(s.disconnectGrace().toSeconds(), s.turnTimeLimit().toSeconds(), s.maxPlayers(), s.allowLateJoin());
        }
    }

    public static RoomState from(GameRoom room) {
        return new RoomState(
                room.getCode(),
                room.getHostId(),
                SettingsView.from(room.getSettings()),
                room.getPlayers().stream().map(PlayerView::from).toList(),
                room.getWaitingPlayers().stream().map(PlayerView::from).toList(),
                room.getCurrentTurnIndex(),
                room.getCurrentTrickType(),
                room.getCurrentTrickCount(),
                room.getCurrentTrickPlayerId(),
                room.getCurrentTrickJesterCount(),
                room.isGameStarted(),
                room.isGameOver(),
                List.copyOf(room.getFinishOrder()),
                room.isTaxPhase(),
                Map.copyOf(room.getPendingTaxReturns()),
                room.isRevolutionPending(),
                room.getRevolution(),
                room.getRevolutionDeclarerId(),
                room.isSeatDrawPhase(),
                Map.copyOf(room.getSeatDraws()),
                room.turnRemainingMillis(Instant.now(), room.getSettings().turnTimeLimit()),
                room.getTimedOutPlayerId(),
                room.getTimeoutCount()
        );
    }
}
