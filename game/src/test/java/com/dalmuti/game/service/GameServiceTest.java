package com.dalmuti.game.service;

import com.dalmuti.game.exception.GameException;
import com.dalmuti.game.model.GameRoom;
import com.dalmuti.game.model.Player;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GameServiceTest {

    private static final Duration TTL = Duration.ofMinutes(5);
    private final GameService service = new GameService(Duration.ofMinutes(1), TTL);

    private List<String> playerIds(GameRoom room) {
        return room.getPlayers().stream().map(Player::getId).toList();
    }

    @Test
    void creatorBecomesHostAndJoinsByCode() {
        String code = service.createRoom("a");
        assertEquals(8, code.length());

        GameRoom room = service.join(code, new Player("a", "A"), "s1");
        service.join(code, new Player("b", "B"), "s2");
        assertEquals("a", room.getHostId());
        assertEquals(List.of("a", "b"), playerIds(room));
    }

    @Test
    void wrongCodeIsRejected() {
        service.createRoom("a");
        GameException e = assertThrows(GameException.class, () -> service.join("wrong", new Player("b", "B"), "s1"));
        assertEquals(GameException.ROOM_NOT_FOUND, e.getCode());
        assertThrows(GameException.class, () -> service.getRoom("wrong"));
    }

    @Test
    void cannotCreateWhileRoomIsInUse() {
        String code = service.createRoom("a");
        service.join(code, new Player("a", "A"), "s1");
        assertThrows(GameException.class, () -> service.createRoom("b"));
    }

    @Test
    void hostCanReplaceOwnEmptyRoomButOthersWaitForExpiry() {
        String first = service.createRoom("a");  // 만들고 아무도 안 들어옴

        // 다른 사람은 빈 방이 유지되는 동안 새로 못 만듦
        assertFalse(service.canCreateRoom("b"));
        assertThrows(GameException.class, () -> service.createRoom("b"));

        // 방장 본인은 버리고 새로 만들 수 있음
        String second = service.createRoom("a");
        assertNotEquals(first, second);
        assertThrows(GameException.class, () -> service.getRoom(first));

        // 유지 시간이 지나 삭제되면 누구나 만들 수 있음
        assertTrue(service.expireEmptyRoom(Instant.now().plus(TTL)));
        assertTrue(service.canCreateRoom("b"));
    }

    @Test
    void lobbyDisconnectRemovesPlayer() {
        String code = service.createRoom("a");
        GameRoom room = service.join(code, new Player("a", "A"), "s1");
        service.join(code, new Player("b", "B"), "s2");

        assertTrue(service.disconnect("s1").isPresent());
        assertEquals(List.of("b"), playerIds(room));
        assertEquals("b", room.getHostId());  // 방장이 나가면 다음 사람에게
    }

    @Test
    void inGameDisconnectKeepsPlayerForReconnect() {
        String code = service.createRoom("a");
        GameRoom room = service.join(code, new Player("a", "A"), "s1");
        service.join(code, new Player("b", "B"), "s2");
        room.startGame("a");

        service.disconnect("s1");
        assertEquals(List.of("a", "b"), playerIds(room));
        assertFalse(room.getPlayers().get(0).isConnected());

        service.join(code, new Player("a", "A"), "s3");
        assertTrue(room.getPlayers().get(0).isConnected());
    }

    // 모바일에서 다 같이 잠깐 앱을 벗어나는 경우: 방과 판이 유지되고 돌아오면 이어서 진행
    @Test
    void allDisconnectedKeepsRoomUntilExpiry() {
        String code = service.createRoom("a");
        GameRoom room = service.join(code, new Player("a", "A"), "s1");
        service.join(code, new Player("b", "B"), "s2");
        room.startGame("a");

        service.disconnect("s1");
        service.disconnect("s2");
        assertSame(room, service.getRoom(code));
        assertTrue(room.isGameStarted());
        assertFalse(service.expireEmptyRoom(Instant.now().plusSeconds(60)));  // 유지 시간 전

        // 돌아오면 이어서 진행, 빈 방 타이머도 해제
        service.join(code, new Player("b", "B"), "s3");
        assertTrue(room.getPlayers().stream().anyMatch(p -> p.getId().equals("b") && p.isConnected()));
        assertFalse(service.expireEmptyRoom(Instant.now().plus(TTL).plusSeconds(1)));
    }

    @Test
    void emptyRoomIsRemovedAfterTtl() {
        String code = service.createRoom("a");
        GameRoom room = service.join(code, new Player("a", "A"), "s1");
        service.join(code, new Player("b", "B"), "s2");
        room.startGame("a");
        service.disconnect("s1");
        service.disconnect("s2");

        assertTrue(service.expireEmptyRoom(Instant.now().plus(TTL)));
        assertTrue(service.currentRoom().isEmpty());
        assertThrows(GameException.class, () -> service.getRoom(code));
        assertDoesNotThrow(() -> service.createRoom("c"));
    }

    @Test
    void waitingPlayerDisconnectIsHandled() {
        String code = service.createRoom("a");
        GameRoom room = service.join(code, new Player("a", "A"), "s1");
        service.join(code, new Player("b", "B"), "s2");
        room.startGame("a");
        service.join(code, new Player("c", "C"), "s3");
        assertEquals(1, room.getWaitingPlayers().size());

        assertTrue(service.disconnect("s3").isPresent());
        assertTrue(room.getWaitingPlayers().isEmpty());
        assertFalse(service.isNameTaken("C", null));
    }

    @Test
    void otherTabKeepsPlayerConnected() {
        String code = service.createRoom("a");
        GameRoom room = service.join(code, new Player("a", "A"), "tab1");
        service.join(code, new Player("a", "A"), "tab2");

        service.disconnect("tab1");
        assertEquals(List.of("a"), playerIds(room));
        assertSame(room, service.getRoom(code));
    }

    @Test
    void disconnectIsIdempotent() {
        String code = service.createRoom("a");
        service.join(code, new Player("a", "A"), "s1");
        service.join(code, new Player("b", "B"), "s2");
        service.disconnect("s1");
        assertTrue(service.disconnect("s1").isEmpty());
    }

    @Test
    void failedJoinIsNotTracked() {
        String code = service.createRoom("a");
        GameRoom room = service.join(code, new Player("a", "A"), "s1");
        service.join(code, new Player("b", "B"), "s2");
        room.startGame("a");

        assertThrows(GameException.class, () -> service.join(code, new Player("c", "A"), "s3"));  // 이름 중복
        // 실패한 입장의 세션은 남지 않음
        assertTrue(service.disconnect("s3").isEmpty());
        assertSame(room, service.getRoom(code));
    }
}
