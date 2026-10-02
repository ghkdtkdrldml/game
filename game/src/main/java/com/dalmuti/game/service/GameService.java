// service/GameService.java
package com.dalmuti.game.service;

import com.dalmuti.game.exception.GameException;
import com.dalmuti.game.model.GameRoom;
import com.dalmuti.game.model.Player;
import com.dalmuti.game.model.RoomSettings;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

// 게임 방은 동시에 하나만 운영. 방을 만든 사람이 방장이고, 방 코드가 든 초대 링크로만 입장
// 접속자가 모두 나가면 방이 사라짐
// 락 순서: GameService → GameRoom (반대 순서로 잡는 곳이 없어야 교착이 생기지 않음)
@Service
public class GameService {
    // 헷갈리는 문자(0/O, 1/l/I) 제외
    private static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
    private static final int CODE_LENGTH = 8;

    private final SecureRandom random = new SecureRandom();
    private final Duration defaultGrace;

    private GameRoom room;  // null이면 방 없음
    // WebSocket 세션 ID → 입장한 playerId
    private final Map<String, String> sessions = new HashMap<>();

    public GameService(@Value("${game.disconnect-grace}") Duration defaultGrace) {
        this.defaultGrace = defaultGrace;
    }

    // 방 생성. 이미 사람이 있는 방이 있으면 거부 (아무도 접속해 있지 않은 방은 새 방으로 교체)
    public synchronized String createRoom(String hostId) {
        if (room != null && room.hasConnectedPlayers()) {
            throw new GameException("이미 진행 중인 방이 있습니다. 초대 링크로 입장하세요.");
        }
        room = new GameRoom(newCode(), hostId, RoomSettings.defaults(defaultGrace));
        sessions.clear();
        return room.getCode();
    }

    public synchronized Optional<GameRoom> currentRoom() {
        return Optional.ofNullable(room);
    }

    public synchronized GameRoom getRoom(String code) {
        if (room == null || !room.getCode().equals(code)) {
            throw new GameException(GameException.ROOM_NOT_FOUND, "방이 없습니다. 링크를 확인하거나 새 방을 만드세요.");
        }
        return room;
    }

    // 로그인 시 이름 중복 확인용. 최종 확인은 입장(join) 시 GameRoom에서 다시 함
    public synchronized boolean isNameTaken(String name, String exceptPlayerId) {
        return room != null && room.isNameTaken(name, exceptPlayerId);
    }

    public synchronized GameRoom join(String code, Player player, String sessionId) {
        GameRoom target = getRoom(code);
        target.addPlayer(player);
        sessions.put(sessionId, player.getId());
        return target;
    }

    // 세션 종료 처리. 방에 남은 접속자가 있으면 상태 갱신을 위해 반환
    public synchronized Optional<GameRoom> disconnect(String sessionId) {
        String playerId = sessions.remove(sessionId);
        if (playerId == null || room == null) return Optional.empty();

        // 같은 플레이어가 다른 탭(세션)으로 아직 접속 중이면 유지
        if (sessions.containsValue(playerId)) return Optional.of(room);

        room.disconnectPlayer(playerId);
        if (!room.hasConnectedPlayers()) {
            room = null;
            sessions.clear();
            return Optional.empty();
        }
        return Optional.of(room);
    }

    private String newCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(CODE_CHARS.charAt(random.nextInt(CODE_CHARS.length())));
        }
        return sb.toString();
    }
}
