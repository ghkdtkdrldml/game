package com.dalmuti.game.model;

import com.dalmuti.game.exception.GameException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static com.dalmuti.game.model.CardType.*;
import static org.junit.jupiter.api.Assertions.*;

class GameRoomTest {

    private GameRoom startedRoom(String... ids) {
        GameRoom room = new GameRoom();
        for (String id : ids) room.addPlayer(new Player(id, id));
        room.startGame(ids[0]);
        return room;
    }

    private void setHand(GameRoom room, int idx, CardType... cards) {
        List<CardType> hand = room.getPlayers().get(idx).getHand();
        hand.clear();
        hand.addAll(List.of(cards));
    }

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final Duration GRACE = Duration.ofSeconds(15);

    @Test
    void awayPlayerIsSkippedOnlyAfterGrace() {
        GameRoom room = startedRoom("a", "b", "c");
        setHand(room, 0, COOK, PEASANT);
        setHand(room, 1, PEASANT);
        setHand(room, 2, PEASANT);

        room.playCards("a", List.of(COOK));
        room.disconnectPlayer("b", T0);

        // 유예 시간 안에는 기다림 (새로고침 중일 수 있음)
        assertFalse(room.actForAwayPlayers(T0.plusSeconds(14), GRACE));
        assertEquals(1, room.getCurrentTurnIndex());

        // 유예 시간이 지나면 자동 패스
        assertTrue(room.actForAwayPlayers(T0.plusSeconds(15), GRACE));
        assertEquals(2, room.getCurrentTurnIndex());
        assertEquals(COOK, room.getCurrentTrickType());
    }

    @Test
    void awayLeadPassesLeadToNextPlayer() {
        GameRoom room = startedRoom("a", "b", "c");
        room.disconnectPlayer("a", T0);  // a가 선

        assertTrue(room.actForAwayPlayers(T0.plus(GRACE), GRACE));
        assertEquals(1, room.getCurrentTurnIndex());
        assertEquals(0, room.getCurrentTrickCount());
    }

    @Test
    void reconnectBeforeGraceCancelsAutoPass() {
        GameRoom room = startedRoom("a", "b");
        room.disconnectPlayer("a", T0);
        room.addPlayer(new Player("a", "a"));

        assertFalse(room.actForAwayPlayers(T0.plus(GRACE), GRACE));
        assertEquals(0, room.getCurrentTurnIndex());
    }

    @Test
    void awayReceiverReturnsWorstTaxCards() {
        GameRoom room = new GameRoom();
        Player dalmuti = new Player("d", "d");
        dalmuti.setRank(Rank.GREAT_DALMUTI);
        Player serf = new Player("s", "s");
        serf.setRank(Rank.SERF);
        room.addPlayer(dalmuti);
        room.addPlayer(serf);
        room.startGame("d", List.of(JESTER, DALMUTI, PEASANT, ARCHBISHOP, COOK, COOK));
        // 달무티: DALMUTI, ARCHBISHOP, COOK, PEASANT, JESTER / 농노: COOK

        room.disconnectPlayer("d", T0);
        assertTrue(room.actForAwayPlayers(T0.plus(GRACE), GRACE));

        assertFalse(room.isTaxPhase());
        assertEquals(List.of(PEASANT, JESTER), room.findTaxExchange("s").orElseThrow().getReturnedCards());
        assertEquals(List.of(COOK, PEASANT, JESTER), serf.getHand());
    }

    @Test
    void gameEndsWhenAllRemainingPlayersAreAway() {
        GameRoom room = startedRoom("a", "b", "c");
        setHand(room, 0, COOK);
        setHand(room, 1, PEASANT);
        setHand(room, 2, PEASANT);

        room.playCards("a", List.of(COOK));  // a 1등, a만 접속 중
        room.disconnectPlayer("b", T0);
        room.disconnectPlayer("c", T0);

        assertTrue(room.actForAwayPlayers(T0.plus(GRACE), GRACE));
        assertTrue(room.isGameOver());
        assertEquals(List.of("a", "b", "c"), room.getFinishOrder());
        // 끊긴 플레이어는 판이 끝나면 정리
        assertEquals(List.of("a"), room.getPlayers().stream().map(Player::getId).toList());
    }

    // 이전 판 신분이 있는 4인 방. 자리 순서: d(달무티), pm(총리대신), tf(소작농), s(농노)
    private GameRoom rankedRoom() {
        GameRoom room = new GameRoom();
        String[] ids = {"d", "pm", "tf", "s"};
        Rank[] ranks = {Rank.GREAT_DALMUTI, Rank.PRIME_MINISTER, Rank.TENANT_FARMER, Rank.SERF};
        for (int i = 0; i < ids.length; i++) {
            Player p = new Player(ids[i], ids[i]);
            p.setRank(ranks[i]);
            room.addPlayer(p);
        }
        return room;
    }

    private Player player(GameRoom room, String id) {
        return room.getPlayers().stream().filter(p -> p.getId().equals(id)).findFirst().orElseThrow();
    }

    private List<String> seatOrder(GameRoom room) {
        return room.getPlayers().stream().map(Player::getId).toList();
    }

    // 4명에게 번갈아 배분 → 3, 7번째 카드가 농노에게 감
    private static final List<CardType> SERF_GETS_JESTERS = List.of(
            DALMUTI, ARCHBISHOP, COOK, JESTER,
            ARCHBISHOP, MASON, PEASANT, JESTER,
            KNIGHT, KNIGHT, KNIGHT, PEASANT);

    // 1, 5번째 카드가 총리대신에게 감
    private static final List<CardType> PM_GETS_JESTERS = List.of(
            DALMUTI, JESTER, COOK, PEASANT,
            ARCHBISHOP, JESTER, PEASANT, PEASANT,
            KNIGHT, KNIGHT, KNIGHT, PEASANT);

    @Test
    void revolutionDecisionBlocksTaxAndPlay() {
        GameRoom room = rankedRoom();
        room.startGame("d", SERF_GETS_JESTERS);

        assertTrue(room.isRevolutionPending());
        assertEquals("s", room.getRevolutionCandidateId());
        assertFalse(room.isTaxPhase());              // 세금은 결정 후에 걷음
        assertTrue(room.getTaxExchanges().isEmpty());

        assertThrows(GameException.class, () -> room.playCards("d", List.of(DALMUTI)));
        assertThrows(GameException.class, () -> room.decideRevolution("d", true));  // 어릿광대 2장 보유자만
    }

    @Test
    void greatRevolutionReversesRanksWithoutTax() {
        GameRoom room = rankedRoom();
        room.startGame("d", SERF_GETS_JESTERS);
        room.decideRevolution("s", true);

        assertEquals(Revolution.GREAT_REVOLUTION, room.getRevolution());
        assertEquals("s", room.getRevolutionDeclarerId());
        assertFalse(room.isRevolutionPending());
        assertFalse(room.isTaxPhase());
        assertTrue(room.getTaxExchanges().isEmpty());

        assertEquals(Rank.GREAT_DALMUTI, player(room, "s").getRank());
        assertEquals(Rank.PRIME_MINISTER, player(room, "tf").getRank());
        assertEquals(Rank.TENANT_FARMER, player(room, "pm").getRank());
        assertEquals(Rank.SERF, player(room, "d").getRank());
        // 새 신분 순 자리, 새 달무티가 선
        assertEquals(List.of("s", "tf", "pm", "d"), seatOrder(room));
        assertEquals(0, room.getCurrentTurnIndex());
        assertEquals(List.of(PEASANT, JESTER, JESTER), player(room, "s").getHand());  // 손패 그대로
        assertDoesNotThrow(() -> room.playCards("s", List.of(PEASANT)));
    }

    @Test
    void revolutionByNonSerfCancelsTaxOnly() {
        GameRoom room = rankedRoom();
        room.startGame("d", PM_GETS_JESTERS);
        assertEquals("pm", room.getRevolutionCandidateId());

        room.decideRevolution("pm", true);

        assertEquals(Revolution.REVOLUTION, room.getRevolution());
        assertFalse(room.isTaxPhase());
        assertEquals(Rank.GREAT_DALMUTI, player(room, "d").getRank());  // 신분 유지
        assertEquals(List.of("d", "pm", "tf", "s"), seatOrder(room));
        assertDoesNotThrow(() -> room.playCards("d", List.of(DALMUTI)));
    }

    @Test
    void decliningRevolutionCollectsTax() {
        GameRoom room = rankedRoom();
        room.startGame("d", SERF_GETS_JESTERS);
        room.decideRevolution("s", false);

        assertNull(room.getRevolution());
        assertTrue(room.isTaxPhase());
        // 농노의 가장 좋은 카드 2장 (어릿광대는 13이라 가장 나쁨)
        assertEquals(List.of(PEASANT, JESTER), room.findTaxExchange("s").orElseThrow().getPaidCards());
        assertEquals(Map.of("d", 2, "pm", 1), room.getPendingTaxReturns());
    }

    @Test
    void firstGameHasNoRevolutionDecision() {
        GameRoom room = new GameRoom();
        for (String id : List.of("a", "b", "c", "d")) room.addPlayer(new Player(id, id));
        room.startGame("a", SERF_GETS_JESTERS);  // d가 어릿광대 2장을 받지만 전원 평민

        assertFalse(room.isRevolutionPending());
        assertDoesNotThrow(() -> room.playCards("a", List.of(DALMUTI)));
    }

    @Test
    void awayRevolutionCandidateAutoDeclines() {
        GameRoom room = rankedRoom();
        room.startGame("d", SERF_GETS_JESTERS);
        room.disconnectPlayer("s", T0);

        assertFalse(room.actForAwayPlayers(T0.plusSeconds(14), GRACE));
        assertTrue(room.isRevolutionPending());

        assertTrue(room.actForAwayPlayers(T0.plus(GRACE), GRACE));
        assertFalse(room.isRevolutionPending());
        assertNull(room.getRevolution());
        assertTrue(room.isTaxPhase());  // 평소대로 세금 교환 진행
    }

    @Test
    void cannotPlayCardsNotInHand() {
        GameRoom room = startedRoom("a", "b");
        setHand(room, 0, PEASANT, PEASANT);
        setHand(room, 1, PEASANT);

        assertThrows(GameException.class, () -> room.playCards("a", List.of(DALMUTI)));
        assertThrows(GameException.class, () -> room.playCards("a", List.of(PEASANT, PEASANT, PEASANT)));
        assertDoesNotThrow(() -> room.playCards("a", List.of(PEASANT, PEASANT)));
    }

    @Test
    void rejectsInvalidMoves() {
        GameRoom room = startedRoom("a", "b");
        setHand(room, 0, COOK, COOK, MASON);
        setHand(room, 1, PEASANT, PEASANT, KNIGHT, KNIGHT, SEAMSTRESS);

        assertThrows(GameException.class, () -> room.playCards("b", List.of(PEASANT)));      // 내 턴 아님
        assertThrows(GameException.class, () -> room.playCards("a", List.of(COOK, MASON)));  // 종류 혼합
        room.playCards("a", List.of(COOK, COOK));

        assertThrows(GameException.class, () -> room.playCards("b", List.of(SEAMSTRESS)));       // 장수 불일치
        assertThrows(GameException.class, () -> room.playCards("b", List.of(PEASANT, PEASANT))); // 더 높은 숫자
        assertDoesNotThrow(() -> room.playCards("b", List.of(KNIGHT, KNIGHT)));
    }

    @Test
    void leadCannotPass() {
        GameRoom room = startedRoom("a", "b");
        assertThrows(GameException.class, () -> room.pass("a"));
    }

    @Test
    void startRequiresMemberAndMinPlayers() {
        GameRoom room = new GameRoom();
        room.addPlayer(new Player("a", "a"));
        assertThrows(GameException.class, () -> room.startGame("a"));   // 인원 부족
        room.addPlayer(new Player("b", "b"));
        assertThrows(GameException.class, () -> room.startGame("x"));   // 방 참가자 아님
        room.startGame("a");
        assertThrows(GameException.class, () -> room.startGame("a"));   // 중복 시작
        assertThrows(GameException.class, () -> room.addPlayer(new Player("c", "c"))); // 진행 중 입장
    }

    @Test
    void taxIsCollectedAndReturned() {
        GameRoom room = new GameRoom();
        String[] ids = {"a", "b", "c", "d"};
        Rank[] ranks = {Rank.SERF, Rank.GREAT_DALMUTI, Rank.TENANT_FARMER, Rank.PRIME_MINISTER};
        for (int i = 0; i < ids.length; i++) {
            Player p = new Player(ids[i], ids[i]);
            p.setRank(ranks[i]);
            room.addPlayer(p);
        }
        room.startGame("a");
        // 무작위 배분이라 누군가 어릿광대 2장을 받았을 수 있음 → 혁명하지 않음으로 진행
        if (room.isRevolutionPending()) room.decideRevolution(room.getRevolutionCandidateId(), false);

        // 신분 순 자리: 달무티(b), 총리대신(d), 소작농(c), 농노(a)
        assertEquals(List.of("b", "d", "c", "a"), room.getPlayers().stream().map(Player::getId).toList());
        assertTrue(room.isTaxPhase());
        assertEquals(Map.of("b", 2, "d", 1), room.getPendingTaxReturns());
        assertEquals(List.of(22, 21, 19, 18), handSizes(room));

        assertThrows(GameException.class, () -> room.playCards("b", List.of(room.getPlayers().get(0).getHand().get(0))));
        assertThrows(GameException.class, () -> room.returnTax("a", List.of(PEASANT)));           // 반환 의무 없음
        List<CardType> bHand = room.getPlayers().get(0).getHand();
        assertThrows(GameException.class, () -> room.returnTax("b", List.of(bHand.get(21))));      // 장수 부족

        room.returnTax("b", List.of(bHand.get(20), bHand.get(21)));
        assertTrue(room.isTaxPhase());
        List<CardType> dHand = room.getPlayers().get(1).getHand();
        room.returnTax("d", List.of(dHand.get(20)));

        assertFalse(room.isTaxPhase());
        assertEquals(List.of(20, 20, 20, 20), handSizes(room));
        assertDoesNotThrow(() -> room.playCards("b", List.of(bHand.get(0))));  // 달무티가 선
    }

    @Test
    void serfPaysBestCards() {
        GameRoom room = new GameRoom();
        Player dalmuti = new Player("d", "d");
        dalmuti.setRank(Rank.GREAT_DALMUTI);
        Player serf = new Player("s", "s");
        serf.setRank(Rank.SERF);
        room.addPlayer(dalmuti);
        room.addPlayer(serf);
        // 번갈아 배분: 달무티 ← JESTER, PEASANT, PEASANT / 농노 ← DALMUTI, ARCHBISHOP, COOK
        room.startGame("d", List.of(JESTER, DALMUTI, PEASANT, ARCHBISHOP, PEASANT, COOK));

        assertEquals(List.of(COOK), serf.getHand());
        assertEquals(List.of(DALMUTI, ARCHBISHOP, PEASANT, PEASANT, JESTER), dalmuti.getHand());

        // 교환 기록은 양쪽 모두에서 조회 가능
        TaxExchange exchange = room.findTaxExchange("s").orElseThrow();
        assertSame(exchange, room.findTaxExchange("d").orElseThrow());
        assertEquals(List.of(DALMUTI, ARCHBISHOP), exchange.getPaidCards());
        assertTrue(exchange.isPending());

        // 달무티는 아무 카드나 2장 돌려줄 수 있음
        room.returnTax("d", List.of(PEASANT, JESTER));
        assertEquals(List.of(COOK, PEASANT, JESTER), serf.getHand());
        assertEquals(List.of(PEASANT, JESTER), exchange.getReturnedCards());
        assertFalse(room.isTaxPhase());
    }

    private List<Integer> handSizes(GameRoom room) {
        return room.getPlayers().stream().map(p -> p.getHand().size()).toList();
    }

    @Test
    void finishedPlayerIsSkippedAndNextPlayerLeads() {
        GameRoom room = startedRoom("a", "b", "c");
        setHand(room, 0, COOK);
        setHand(room, 1, PEASANT, PEASANT);
        setHand(room, 2, PEASANT, PEASANT);

        room.playCards("a", List.of(COOK));   // a 1등
        assertEquals(List.of("a"), room.getFinishOrder());
        assertEquals(1, room.getCurrentTurnIndex());

        room.pass("b");
        room.pass("c");

        // 전원 패스 → a는 이미 나갔으므로 b가 빈 바닥에 선
        assertEquals(1, room.getCurrentTurnIndex());
        assertEquals(0, room.getCurrentTrickCount());
        assertNull(room.getCurrentTrickType());
    }

    @Test
    void trickReturnsToLastPlayerWhenOthersPass() {
        GameRoom room = startedRoom("a", "b", "c");
        setHand(room, 0, COOK, PEASANT);
        setHand(room, 1, PEASANT);
        setHand(room, 2, PEASANT);

        room.playCards("a", List.of(COOK));
        room.pass("b");
        room.pass("c");

        assertEquals(0, room.getCurrentTurnIndex());
        assertEquals(0, room.getCurrentTrickCount());
    }

    @Test
    void gameEndsAndRanksAssigned() {
        GameRoom room = startedRoom("a", "b", "c", "d");
        setHand(room, 0, DALMUTI);
        setHand(room, 1, ARCHBISHOP, PEASANT);
        setHand(room, 2, PEASANT);
        setHand(room, 3, PEASANT, PEASANT);

        room.playCards("a", List.of(DALMUTI));  // a 1등
        room.pass("b");
        room.pass("c");
        room.pass("d");
        // b 선
        room.playCards("b", List.of(PEASANT));
        room.pass("c");
        room.pass("d");
        room.playCards("b", List.of(ARCHBISHOP)); // b 2등
        room.pass("c");
        room.pass("d");
        // c 선
        room.playCards("c", List.of(PEASANT));   // c 3등 → d 꼴찌, 종료

        assertTrue(room.isGameOver());
        assertFalse(room.isGameStarted());
        assertEquals(List.of("a", "b", "c", "d"), room.getFinishOrder());
        assertEquals(Rank.GREAT_DALMUTI, room.getPlayers().get(0).getRank());
        assertEquals(Rank.PRIME_MINISTER, room.getPlayers().get(1).getRank());
        assertEquals(Rank.TENANT_FARMER, room.getPlayers().get(2).getRank());
        assertEquals(Rank.SERF, room.getPlayers().get(3).getRank());

        // 다음 판은 신분 순 자리, 달무티가 선
        room.startGame("a");
        assertEquals("a", room.getPlayers().get(0).getId());
        assertEquals("d", room.getPlayers().get(3).getId());
        assertTrue(room.getFinishOrder().isEmpty());
    }
}
