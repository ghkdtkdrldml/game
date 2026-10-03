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
        room.startGame(ids[0], GameRoom.newShuffledDeck());  // 자리 뽑기 없이 바로 배분
        return room;
    }

    // ---------- 방장 ----------

    private GameRoom lobby(String... ids) {
        GameRoom room = new GameRoom();
        for (String id : ids) room.addPlayer(new Player(id, id));
        return room;
    }

    @Test
    void firstJoinerIsHostAndOnlyHostCanStart() {
        GameRoom room = lobby("a", "b");
        assertEquals("a", room.getHostId());
        assertThrows(GameException.class, () -> room.startGame("b"));
        assertDoesNotThrow(() -> room.startGame("a"));
    }

    @Test
    void hostCanTransferToConnectedMember() {
        GameRoom room = lobby("a", "b", "c");
        assertThrows(GameException.class, () -> room.transferHost("b", "c"));  // 방장만
        room.transferHost("a", "b");
        assertEquals("b", room.getHostId());
        assertThrows(GameException.class, () -> room.transferHost("b", "x"));  // 방에 없는 사람
    }

    @Test
    void hostLeavingLobbyPassesHostImmediately() {
        GameRoom room = lobby("a", "b", "c");
        room.disconnectPlayer("a", T0);
        assertEquals("b", room.getHostId());  // 가장 먼저 들어온 접속자
    }

    @Test
    void hostDisconnectedInGamePassesHostAfterGrace() {
        GameRoom room = startedRoom("a", "b", "c");
        room.disconnectPlayer("a", T0);

        assertFalse(room.updateHost(T0.plusSeconds(14), GRACE));
        assertEquals("a", room.getHostId());
        assertTrue(room.updateHost(T0.plus(GRACE), GRACE));
        assertEquals("b", room.getHostId());
    }

    @Test
    void kickInLobbyRemovesAndBlocksRejoin() {
        GameRoom room = lobby("a", "b");
        assertThrows(GameException.class, () -> room.kick("b", "a"));  // 방장만
        assertThrows(GameException.class, () -> room.kick("a", "a"));  // 자기 자신 불가

        room.kick("a", "b");
        assertEquals(List.of("a"), seatOrder(room));
        GameException e = assertThrows(GameException.class, () -> room.addPlayer(new Player("b", "b")));
        assertEquals(GameException.KICKED, e.getCode());
    }

    @Test
    void kickWaitingPlayerRemovesImmediately() {
        GameRoom room = startedRoom("a", "b");
        room.addPlayer(new Player("c", "c"));
        room.kick("a", "c");
        assertTrue(room.getWaitingPlayers().isEmpty());
    }

    @Test
    void kickDuringGameAutoPassesThenRemovesAtRoundEnd() {
        GameRoom room = startedRoom("a", "b", "c");
        setHand(room, 0, COOK, PEASANT);
        setHand(room, 1, PEASANT);
        setHand(room, 2, KNIGHT);

        room.playCards("a", List.of(COOK));
        room.kick("a", "b");  // b 차례에 강퇴

        assertTrue(player(room, "b").isKicked());
        assertThrows(GameException.class, () -> room.pass("b"));  // 강퇴된 사람은 조작 불가
        // 유예 시간 없이 바로 자동 패스
        assertTrue(room.actForAwayPlayers(T0, GRACE));
        assertEquals(2, room.getCurrentTurnIndex());

        room.playCards("c", List.of(KNIGHT));  // c 1등
        room.pass("a");                         // 12로는 6을 못 이김 → b 차례
        room.actForAwayPlayers(T0, GRACE);      // b 자동 패스 → 바닥 비우고 a가 선
        room.playCards("a", List.of(PEASANT));  // a 2등, b 꼴찌로 종료
        assertTrue(room.isGameOver());
        assertEquals(List.of("a", "c"), room.getPlayers().stream().map(Player::getId).sorted().toList());
    }

    @Test
    void abortRoundRestoresRanksAndReturnsToLobby() {
        GameRoom room = rankedRoom();
        room.startGame("d", SERF_GETS_JESTERS);
        room.decideRevolution("s", true);  // 대혁명으로 신분이 뒤집힌 상태
        room.addPlayer(new Player("w", "w"));  // 관전자
        room.disconnectPlayer("tf", T0);

        assertThrows(GameException.class, () -> room.abortRound("pm"));  // 방장만
        room.abortRound("d");

        assertFalse(room.isGameStarted());
        assertFalse(room.isGameOver());
        assertEquals(Rank.GREAT_DALMUTI, player(room, "d").getRank());  // 판 시작 전 신분으로
        assertEquals(Rank.SERF, player(room, "s").getRank());
        assertTrue(room.getPlayers().stream().allMatch(p -> p.getHand().isEmpty()));
        // 끊긴 플레이어(tf) 정리, 관전자(w, 평민) 합류, 신분 순 정렬
        assertEquals(List.of("d", "pm", "w", "s"), seatOrder(room));
    }

    // ---------- 차례 제한 시간 ----------

    private static final Duration LIMIT = Duration.ofSeconds(30);

    @Test
    void turnTimesOutToAutoPass() {
        GameRoom room = startedRoom("a", "b", "c");
        setHand(room, 0, COOK, PEASANT);
        setHand(room, 1, KNIGHT);
        setHand(room, 2, KNIGHT);
        room.playCards("a", List.of(COOK));
        room.syncTurnTimer(T0);  // b 차례 시작

        assertFalse(room.enforceTurnTimeLimit(T0.plusSeconds(29), LIMIT));
        assertEquals(1, room.getCurrentTurnIndex());
        assertEquals(1000, room.turnRemainingMillis(T0.plusSeconds(29), LIMIT));

        assertTrue(room.enforceTurnTimeLimit(T0.plusSeconds(30), LIMIT));
        assertEquals(2, room.getCurrentTurnIndex());  // b 자동 패스 → c
        assertEquals("b", room.getTimedOutPlayerId());
        assertEquals(1, room.getTimeoutCount());
        assertEquals(COOK, room.getCurrentTrickType());  // 바닥은 그대로
        // c는 새로 30초
        assertEquals(30_000, room.turnRemainingMillis(T0.plusSeconds(30), LIMIT));
    }

    @Test
    void leadTimeoutPassesLeadToNextPlayer() {
        GameRoom room = startedRoom("a", "b");
        room.syncTurnTimer(T0);  // a가 선

        assertTrue(room.enforceTurnTimeLimit(T0.plus(LIMIT), LIMIT));
        assertEquals(1, room.getCurrentTurnIndex());
        assertEquals(0, room.getCurrentTrickCount());
    }

    @Test
    void timerRestartsWhenSamePlayerGetsTurnAgain() {
        GameRoom room = startedRoom("a", "b");
        setHand(room, 0, COOK, PEASANT);
        setHand(room, 1, PEASANT);
        room.syncTurnTimer(T0);
        room.playCards("a", List.of(COOK));
        room.syncTurnTimer(T0.plusSeconds(20));
        room.pass("b");                          // 다시 a 차례 (같은 사람이지만 새 차례)
        room.syncTurnTimer(T0.plusSeconds(25));

        assertEquals(0, room.getCurrentTurnIndex());
        assertEquals(30_000, room.turnRemainingMillis(T0.plusSeconds(25), LIMIT));
    }

    @Test
    void noTimerOutsidePlayingPhaseOrWithoutLimit() {
        GameRoom room = rankedRoom();
        room.startGame("d", SERF_GETS_JESTERS);  // 혁명 결정 단계
        room.syncTurnTimer(T0);
        assertFalse(room.enforceTurnTimeLimit(T0.plusSeconds(600), LIMIT));
        assertEquals(-1, room.turnRemainingMillis(T0, LIMIT));

        GameRoom playing = startedRoom("a", "b");
        playing.syncTurnTimer(T0);
        assertFalse(playing.enforceTurnTimeLimit(T0.plusSeconds(600), Duration.ZERO));  // 제한 없음
    }

    @Test
    void timerPausesWhileEveryoneIsAway() {
        GameRoom room = startedRoom("a", "b");
        room.syncTurnTimer(T0);
        room.disconnectPlayer("a", T0);
        room.disconnectPlayer("b", T0);

        assertFalse(room.enforceTurnTimeLimit(T0.plusSeconds(300), LIMIT));
        // 돌아오면 그 시점부터 다시 30초
        room.addPlayer(new Player("a", "a"));
        assertFalse(room.enforceTurnTimeLimit(T0.plusSeconds(310), LIMIT));
        assertTrue(room.enforceTurnTimeLimit(T0.plusSeconds(330), LIMIT));
    }

    @Test
    void settingsAreHostOnlyAndValidated() {
        GameRoom room = lobby("a", "b", "c");
        RoomSettings strict = new RoomSettings(Duration.ofSeconds(30), Duration.ofSeconds(30), 4, false);
        assertThrows(GameException.class, () -> room.updateSettings("b", strict));
        room.updateSettings("a", strict);

        assertThrows(GameException.class, () -> room.updateSettings("a", new RoomSettings(Duration.ofSeconds(30), Duration.ofSeconds(30), 2, true)));  // 현재 3명
        assertThrows(GameException.class, () -> new RoomSettings(Duration.ofSeconds(5), Duration.ofSeconds(30), 4, true));
        assertThrows(GameException.class, () -> new RoomSettings(Duration.ofSeconds(30), Duration.ofSeconds(30), 11, true));

        // 게임 중 입장 불가 설정
        room.startGame("a");
        assertThrows(GameException.class, () -> room.addPlayer(new Player("d", "d")));
    }

    @Test
    void maxPlayersSettingLimitsJoin() {
        GameRoom room = lobby("a", "b");
        room.updateSettings("a", new RoomSettings(Duration.ofMinutes(1), Duration.ofSeconds(30), 2, true));
        assertThrows(GameException.class, () -> room.addPlayer(new Player("c", "c")));
    }

    // ---------- 자리 뽑기 (첫 판 신분 정하기) ----------

    @Test
    void firstGameStartsWithSeatDraw() {
        GameRoom room = new GameRoom();
        for (String id : List.of("a", "b", "c")) room.addPlayer(new Player(id, id));
        room.startGame("a");

        assertTrue(room.isGameStarted());
        assertTrue(room.isSeatDrawPhase());
        assertTrue(room.getPlayers().stream().allMatch(p -> p.getHand().isEmpty()));
        assertThrows(GameException.class, () -> room.pass("a"));

        room.drawSeatCard("a");
        assertThrows(GameException.class, () -> room.drawSeatCard("a"));  // 한 번만
        assertThrows(GameException.class, () -> room.drawSeatCard("x"));  // 참가자만
    }

    @Test
    void seatDrawRanksByLowestCardAndSkipsTaxOnFirstRound() {
        GameRoom room = new GameRoom();
        for (String id : List.of("a", "b", "c", "d")) room.addPlayer(new Player(id, id));
        room.startSeatDraw("a", List.of(COOK, DALMUTI, KNIGHT, PEASANT));

        room.drawSeatCard("a");  // 9
        room.drawSeatCard("b");  // 1
        room.drawSeatCard("c");  // 6
        assertTrue(room.isSeatDrawPhase());
        room.drawSeatCard("d");  // 12 → 모두 뽑음

        assertFalse(room.isSeatDrawPhase());
        // 낮은 숫자 순: b(1) 달무티, c(6) 총리대신, a(9) 소작농, d(12) 농노
        assertEquals(List.of("b", "c", "a", "d"), seatOrder(room));
        assertEquals(Rank.GREAT_DALMUTI, player(room, "b").getRank());
        assertEquals(Rank.PRIME_MINISTER, player(room, "c").getRank());
        assertEquals(Rank.TENANT_FARMER, player(room, "a").getRank());
        assertEquals(Rank.SERF, player(room, "d").getRank());
        assertEquals(0, room.getCurrentTurnIndex());  // 달무티가 선

        // 카드 배분, 첫 판은 세금·혁명 없음
        assertEquals(List.of(20, 20, 20, 20), handSizes(room));
        assertFalse(room.isTaxPhase());
        assertFalse(room.isRevolutionPending());
        assertEquals(DALMUTI, room.getSeatDraws().get("b"));  // 결과는 표시용으로 유지
    }

    @Test
    void seatDrawResultsClearedOnNextRound() {
        GameRoom room = new GameRoom();
        for (String id : List.of("a", "b")) room.addPlayer(new Player(id, id));
        room.startSeatDraw("a", List.of(DALMUTI, ARCHBISHOP));
        room.drawSeatCard("a");
        room.drawSeatCard("b");

        setHand(room, 0, COOK);
        setHand(room, 1, PEASANT);
        room.playCards("a", List.of(COOK));  // 종료
        assertTrue(room.isGameOver());

        room.startGame("a");  // 신분이 있으므로 자리 뽑기 없이 바로 배분
        assertFalse(room.isSeatDrawPhase());
        assertTrue(room.getSeatDraws().isEmpty());
    }

    @Test
    void awayPlayerAutoDrawsSeatCard() {
        GameRoom room = new GameRoom();
        for (String id : List.of("a", "b")) room.addPlayer(new Player(id, id));
        room.startSeatDraw("a", List.of(DALMUTI, ARCHBISHOP));
        room.drawSeatCard("a");
        room.disconnectPlayer("b", T0);

        assertFalse(room.actForAwayPlayers(T0.plusSeconds(14), GRACE));
        assertTrue(room.actForAwayPlayers(T0.plus(GRACE), GRACE));
        assertFalse(room.isSeatDrawPhase());
        assertEquals(ARCHBISHOP, room.getSeatDraws().get("b"));
    }

    @Test
    void playerJoiningDuringSeatDrawWaits() {
        GameRoom room = new GameRoom();
        for (String id : List.of("a", "b")) room.addPlayer(new Player(id, id));
        room.startSeatDraw("a", List.of(DALMUTI, ARCHBISHOP, COOK));
        room.addPlayer(new Player("c", "c"));
        room.drawSeatCard("a");
        room.drawSeatCard("b");

        assertFalse(room.isSeatDrawPhase());  // c는 기다리지 않음
        assertEquals(List.of("c"), room.getWaitingPlayers().stream().map(Player::getId).toList());
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

    // 전원이 끊기면 자동 진행을 멈추고 그대로 둠 → 돌아오면 이어서
    @Test
    void autoActionsPauseWhileEveryoneIsAway() {
        GameRoom room = startedRoom("a", "b");
        room.disconnectPlayer("a", T0);
        room.disconnectPlayer("b", T0);

        assertFalse(room.actForAwayPlayers(T0.plus(GRACE).plusSeconds(600), GRACE));
        assertTrue(room.isGameStarted());
        assertFalse(room.isGameOver());
        assertEquals(0, room.getCurrentTurnIndex());

        // 한 명이 돌아오면 다시 자동 진행 (끊긴 a의 차례는 넘어감)
        room.addPlayer(new Player("b", "b"));
        assertTrue(room.actForAwayPlayers(T0.plus(GRACE), GRACE));
        assertEquals(1, room.getCurrentTurnIndex());
    }

    // ---------- 평민 순서 (1등 시민, 2등 시민 ...) ----------

    @Test
    void citizensAreNumberedByFinishOrder() {
        // 6인: 앞 사람보다 낮은 카드를 한 장씩 내며 차례대로 1등~5등, 남은 f가 꼴찌
        GameRoom room = startedRoom("a", "b", "c", "d", "e", "f");
        setHand(room, 0, STONECUTTER);
        setHand(room, 1, COOK);
        setHand(room, 2, KNIGHT);
        setHand(room, 3, BARONESS);
        setHand(room, 4, ARCHBISHOP);
        setHand(room, 5, PEASANT);
        room.playCards("a", List.of(STONECUTTER));
        room.playCards("b", List.of(COOK));
        room.playCards("c", List.of(KNIGHT));
        room.playCards("d", List.of(BARONESS));
        room.playCards("e", List.of(ARCHBISHOP));

        assertTrue(room.isGameOver());
        assertEquals(Rank.GREAT_DALMUTI, player(room, "a").getRank());
        assertEquals(Rank.PRIME_MINISTER, player(room, "b").getRank());
        assertEquals(Rank.CITIZEN, player(room, "c").getRank());
        assertEquals(1, player(room, "c").getCitizenNo());  // 3등 = 1등 시민
        assertEquals(2, player(room, "d").getCitizenNo());  // 4등 = 2등 시민
        assertEquals(Rank.TENANT_FARMER, player(room, "e").getRank());
        assertEquals(0, player(room, "e").getCitizenNo());
        assertEquals(Rank.SERF, player(room, "f").getRank());
    }

    @Test
    void nextRoundSeatsCitizensByNumberNotPreviousSeat() {
        // 이전 판 자리는 c가 d보다 앞이지만, 이번 순위는 d가 1등 시민 → 다음 판은 d가 앞
        GameRoom room = new GameRoom();
        String[] ids = {"a", "b", "c", "d", "e", "f"};
        Rank[] ranks = {Rank.GREAT_DALMUTI, Rank.PRIME_MINISTER, Rank.CITIZEN, Rank.CITIZEN, Rank.TENANT_FARMER, Rank.SERF};
        int[] citizenNos = {0, 0, 2, 1, 0, 0};
        for (int i = 0; i < ids.length; i++) {
            Player p = new Player(ids[i], ids[i]);
            p.setRank(ranks[i]);
            p.setCitizenNo(citizenNos[i]);
            room.addPlayer(p);
        }
        room.startGame("a", GameRoom.newShuffledDeck());

        assertEquals(List.of("a", "b", "d", "c", "e", "f"), seatOrder(room));
    }

    @Test
    void newcomerCitizenSitsAfterNumberedCitizens() {
        GameRoom room = new GameRoom();
        Player d = new Player("d", "d");
        d.setRank(Rank.GREAT_DALMUTI);
        Player c1 = new Player("c1", "c1");
        c1.setCitizenNo(1);
        Player s = new Player("s", "s");
        s.setRank(Rank.SERF);
        room.addPlayer(d);
        room.addPlayer(new Player("new", "new"));  // 번호 없는 평민 (판 사이에 새로 들어옴)
        room.addPlayer(c1);
        room.addPlayer(s);
        room.startGame("d", GameRoom.newShuffledDeck());

        assertEquals(List.of("d", "c1", "new", "s"), seatOrder(room));
    }

    @Test
    void seatDrawNumbersCitizens() {
        GameRoom room = lobby("a", "b", "c", "d", "e", "f");
        room.startSeatDraw("a", List.of(DALMUTI, ARCHBISHOP, EARL_MARSHAL, BARONESS, ABBESS, KNIGHT));
        for (String id : List.of("a", "b", "c", "d", "e", "f")) room.drawSeatCard(id);

        assertEquals(1, player(room, "c").getCitizenNo());
        assertEquals(2, player(room, "d").getCitizenNo());
        assertEquals(List.of("a", "b", "c", "d", "e", "f"), seatOrder(room));
    }

    @Test
    void greatRevolutionReversesCitizenOrderToo() {
        GameRoom room = new GameRoom();
        String[] ids = {"d", "pm", "c1", "c2", "tf", "s"};
        Rank[] ranks = {Rank.GREAT_DALMUTI, Rank.PRIME_MINISTER, Rank.CITIZEN, Rank.CITIZEN, Rank.TENANT_FARMER, Rank.SERF};
        int[] citizenNos = {0, 0, 1, 2, 0, 0};
        for (int i = 0; i < ids.length; i++) {
            Player p = new Player(ids[i], ids[i]);
            p.setRank(ranks[i]);
            p.setCitizenNo(citizenNos[i]);
            room.addPlayer(p);
        }
        // 6명에게 번갈아 배분 → 5, 11번째 카드가 농노(s)에게
        room.startGame("d", List.of(
                DALMUTI, ARCHBISHOP, COOK, PEASANT, KNIGHT, JESTER,
                MASON, MASON, MASON, MASON, MASON, JESTER));
        room.decideRevolution("s", true);

        assertEquals(List.of("s", "tf", "c2", "c1", "pm", "d"), seatOrder(room));
        assertEquals(1, player(room, "c2").getCitizenNo());
        assertEquals(2, player(room, "c1").getCitizenNo());
    }

    @Test
    void trickCountsJestersMixedIn() {
        GameRoom room = startedRoom("a", "b");
        setHand(room, 0, COOK, COOK, JESTER, PEASANT);
        setHand(room, 1, JESTER, JESTER, KNIGHT);

        room.playCards("a", List.of(COOK, COOK, JESTER));
        assertEquals(COOK, room.getCurrentTrickType());
        assertEquals(3, room.getCurrentTrickCount());
        assertEquals(1, room.getCurrentTrickJesterCount());

        // 바닥이 비면 초기화
        room.pass("b");
        assertEquals(0, room.getCurrentTrickJesterCount());

        // 어릿광대만 낸 경우는 어릿광대 카드 자체로 표시되므로 "포함" 장수는 0
        GameRoom other = startedRoom("x", "y");
        setHand(other, 0, JESTER, JESTER, PEASANT);
        setHand(other, 1, KNIGHT);
        other.playCards("x", List.of(JESTER, JESTER));
        assertEquals(JESTER, other.getCurrentTrickType());
        assertEquals(0, other.getCurrentTrickJesterCount());
    }

    @Test
    void trickRemembersWhoPlayedUntilCleared() {
        GameRoom room = startedRoom("a", "b", "c");
        setHand(room, 0, COOK, PEASANT);
        setHand(room, 1, KNIGHT, PEASANT);
        setHand(room, 2, PEASANT);

        room.playCards("a", List.of(COOK));
        assertEquals("a", room.getCurrentTrickPlayerId());
        room.playCards("b", List.of(KNIGHT));
        assertEquals("b", room.getCurrentTrickPlayerId());

        room.pass("c");
        room.pass("a");  // 전원 패스 → 바닥 비움
        assertNull(room.getCurrentTrickPlayerId());
        assertNull(room.getCurrentTrickType());
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

    // ---------- 이름 중복 ----------

    @Test
    void duplicateNamesAreRejected() {
        GameRoom room = new GameRoom();
        room.addPlayer(new Player("a", "홍길동"));

        GameException e = assertThrows(GameException.class, () -> room.addPlayer(new Player("b", "홍길동")));
        assertEquals(GameException.NAME_TAKEN, e.getCode());
        assertThrows(GameException.class, () -> room.addPlayer(new Player("c", " 홍길동 ")));  // 앞뒤 공백 무시
        room.addPlayer(new Player("d", "Kim"));
        assertThrows(GameException.class, () -> room.addPlayer(new Player("e", "KIM")));       // 대소문자 무시

        assertTrue(room.isNameTaken("홍길동", "x"));
        assertFalse(room.isNameTaken("홍길동", "a"));  // 본인은 제외
    }

    @Test
    void rejoinWithSameIdCanKeepOrChangeName() {
        GameRoom room = new GameRoom();
        room.addPlayer(new Player("a", "홍길동"));
        room.addPlayer(new Player("b", "철수"));

        assertDoesNotThrow(() -> room.addPlayer(new Player("a", "홍길동")));   // 재입장
        room.addPlayer(new Player("a", "길동이"));                              // 이름 변경 후 재입장
        assertEquals("길동이", room.getPlayers().get(0).getName());
        assertThrows(GameException.class, () -> room.addPlayer(new Player("a", "철수")));  // 남의 이름으로 변경 불가
    }

    // ---------- 다음 판 대기 ----------

    @Test
    void joiningDuringGameWaitsForNextRound() {
        GameRoom room = startedRoom("a", "b");
        room.addPlayer(new Player("c", "c"));

        assertEquals(List.of("a", "b"), room.getPlayers().stream().map(Player::getId).toList());
        assertEquals(List.of("c"), room.getWaitingPlayers().stream().map(Player::getId).toList());
        assertEquals(3, room.allMembers().size());
        // 대기자는 카드를 받지 않고 플레이할 수 없음
        assertTrue(room.getWaitingPlayers().get(0).getHand().isEmpty());
        assertThrows(GameException.class, () -> room.pass("c"));
        // 대기자 이름도 중복 불가
        assertTrue(room.isNameTaken("c", "x"));
    }

    @Test
    void waitingPlayerJoinsWhenRoundEnds() {
        GameRoom room = startedRoom("a", "b");
        setHand(room, 0, COOK);
        setHand(room, 1, PEASANT);
        room.addPlayer(new Player("c", "c"));

        room.playCards("a", List.of(COOK));  // a 1등, b 꼴찌 → 종료

        assertTrue(room.isGameOver());
        assertTrue(room.getWaitingPlayers().isEmpty());
        assertEquals(List.of("a", "b", "c"), room.getPlayers().stream().map(Player::getId).toList());
        assertEquals(Rank.CITIZEN, room.getPlayers().get(2).getRank());

        // 다음 판에는 3명 모두 카드를 받음
        room.startGame("a");
        assertTrue(room.getPlayers().stream().noneMatch(p -> p.getHand().isEmpty()));
    }

    @Test
    void waitingPlayerLeavingIsRemovedImmediately() {
        GameRoom room = startedRoom("a", "b");
        room.addPlayer(new Player("c", "c"));

        room.disconnectPlayer("c", T0);
        assertTrue(room.getWaitingPlayers().isEmpty());
        assertFalse(room.isNameTaken("c", "x"));
    }

    @Test
    void roomCapacityIncludesWaitingPlayers() {
        GameRoom room = new GameRoom();
        for (int i = 0; i < 9; i++) room.addPlayer(new Player("p" + i, "p" + i));
        room.startGame("p0");
        room.addPlayer(new Player("w", "w"));  // 10번째: 대기자로 입장

        assertThrows(GameException.class, () -> room.addPlayer(new Player("x", "x")));
    }

    @Test
    void waitingPlayerKeepsRoomAliveAndRoundEndsWhenAllPlayersAway() {
        GameRoom room = startedRoom("a", "b");
        room.addPlayer(new Player("c", "c"));
        room.disconnectPlayer("a", T0);
        room.disconnectPlayer("b", T0);

        assertTrue(room.hasConnectedPlayers());  // 대기자가 남아 있으면 방 유지
        assertTrue(room.actForAwayPlayers(T0.plus(GRACE), GRACE));
        // 판이 끝나고 끊긴 플레이어는 정리, 대기자가 합류
        assertTrue(room.isGameOver());
        assertEquals(List.of("c"), room.getPlayers().stream().map(Player::getId).toList());
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
