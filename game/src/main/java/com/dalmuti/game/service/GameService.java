// service/GameService.java
package com.dalmuti.game.service;

import com.dalmuti.game.model.GameRoom;
import com.dalmuti.game.model.Player;
import org.springframework.stereotype.Service;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

// 게임 방은 하나만 운영. 접속자가 모두 나가면 새 방으로 초기화
// 락 순서: GameService → GameRoom (반대 순서로 잡는 곳이 없어야 교착이 생기지 않음)
@Service
public class GameService {
    private GameRoom room = new GameRoom();
    // WebSocket 세션 ID → 입장한 playerId
    private final Map<String, String> sessions = new HashMap<>();

    public synchronized GameRoom getRoom() {
        return room;
    }

    // 로그인 시 이름 중복 확인용. 최종 확인은 입장(join) 시 GameRoom에서 다시 함
    public synchronized boolean isNameTaken(String name, String exceptPlayerId) {
        return room.isNameTaken(name, exceptPlayerId);
    }

    public synchronized GameRoom join(Player player, String sessionId) {
        room.addPlayer(player);
        sessions.put(sessionId, player.getId());
        return room;
    }

    // 세션 종료 처리. 방에 남은 접속자가 있으면 상태 갱신을 위해 반환
    public synchronized Optional<GameRoom> disconnect(String sessionId) {
        String playerId = sessions.remove(sessionId);
        if (playerId == null) return Optional.empty();

        // 같은 플레이어가 다른 탭(세션)으로 아직 접속 중이면 유지
        if (sessions.containsValue(playerId)) return Optional.of(room);

        room.disconnectPlayer(playerId);
        if (!room.hasConnectedPlayers()) {
            room = new GameRoom();
            return Optional.empty();
        }
        return Optional.of(room);
    }
}
