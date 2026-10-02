package com.dalmuti.game.service;

import com.dalmuti.game.exception.GameException;
import com.dalmuti.game.model.GameRoom;
import com.dalmuti.game.model.Player;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GameServiceTest {

    private final GameService service = new GameService(Duration.ofMinutes(1));

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
    void emptyRoomCanBeReplaced() {
        String first = service.createRoom("a");  // 만들고 아무도 안 들어옴
        String second = service.createRoom("b");
        assertNotEquals(first, second);
        assertEquals("b", service.getRoom(second).getHostId());
        assertThrows(GameException.class, () -> service.getRoom(first));
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

    @Test
    void allDisconnectedRemovesRoom() {
        String code = service.createRoom("a");
        GameRoom room = service.join(code, new Player("a", "A"), "s1");
        service.join(code, new Player("b", "B"), "s2");
        room.startGame("a");

        service.disconnect("s1");
        assertTrue(service.disconnect("s2").isEmpty());

        // 방이 사라져 링크가 더 이상 유효하지 않고, 새 방을 만들 수 있음
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
