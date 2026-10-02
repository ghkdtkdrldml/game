package com.dalmuti.game.service;

import com.dalmuti.game.exception.GameException;
import com.dalmuti.game.model.GameRoom;
import com.dalmuti.game.model.Player;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GameServiceTest {

    private final GameService service = new GameService();

    private List<String> playerIds(GameRoom room) {
        return room.getPlayers().stream().map(Player::getId).toList();
    }

    @Test
    void lobbyDisconnectRemovesPlayer() {
        GameRoom room = service.join(new Player("a", "A"), "s1");
        service.join(new Player("b", "B"), "s2");

        assertTrue(service.disconnect("s1").isPresent());
        assertEquals(List.of("b"), playerIds(room));
    }

    @Test
    void inGameDisconnectKeepsPlayerForReconnect() {
        GameRoom room = service.join(new Player("a", "A"), "s1");
        service.join(new Player("b", "B"), "s2");
        room.startGame("a");

        service.disconnect("s1");
        assertEquals(List.of("a", "b"), playerIds(room));
        assertFalse(room.getPlayers().get(0).isConnected());

        // 같은 ID로 재접속하면 손패 그대로 복귀
        int handSize = room.getPlayers().get(0).getHand().size();
        service.join(new Player("a", "A"), "s3");
        assertTrue(room.getPlayers().get(0).isConnected());
        assertEquals(handSize, room.getPlayers().get(0).getHand().size());
    }

    @Test
    void allDisconnectedResetsRoom() {
        GameRoom room = service.join(new Player("a", "A"), "s1");
        service.join(new Player("b", "B"), "s2");
        room.startGame("a");

        service.disconnect("s1");
        assertTrue(service.disconnect("s2").isEmpty());

        // 진행 중이던 방 대신 새 방으로 초기화되어 다시 입장 가능
        GameRoom fresh = service.getRoom();
        assertNotSame(room, fresh);
        assertFalse(fresh.isGameStarted());
        assertTrue(fresh.getPlayers().isEmpty());
        assertDoesNotThrow(() -> service.join(new Player("c", "C"), "s3"));
    }

    @Test
    void otherTabKeepsPlayerConnected() {
        GameRoom room = service.join(new Player("a", "A"), "tab1");
        service.join(new Player("a", "A"), "tab2");

        service.disconnect("tab1");
        assertEquals(List.of("a"), playerIds(room));
        assertSame(room, service.getRoom());
    }

    @Test
    void disconnectIsIdempotent() {
        service.join(new Player("a", "A"), "s1");
        service.join(new Player("b", "B"), "s2");
        service.disconnect("s1");
        assertTrue(service.disconnect("s1").isEmpty());
    }

    @Test
    void failedJoinIsNotTracked() {
        GameRoom room = service.join(new Player("a", "A"), "s1");
        service.join(new Player("b", "B"), "s2");
        room.startGame("a");

        assertThrows(GameException.class, () -> service.join(new Player("c", "C"), "s3"));
        // 실패한 입장의 세션은 남지 않음
        assertTrue(service.disconnect("s3").isEmpty());
        assertSame(room, service.getRoom());
    }
}
