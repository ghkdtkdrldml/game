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
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

// 게임 방은 동시에 하나만 운영. 방을 만든 사람이 방장이고, 방 코드가 든 초대 링크로만 입장
// 접속자가 모두 나가도 바로 지우지 않고 emptyRoomTtl 동안 유지 (모바일에서 다 같이 잠깐 앱을 벗어나도 판이 남도록)
// 락 순서: GameService → GameRoom (반대 순서로 잡는 곳이 없어야 교착이 생기지 않음)
@Service
public class GameService {
    // 헷갈리는 문자(0/O, 1/l/I) 제외
    private static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
    private static final int CODE_LENGTH = 8;

    private final SecureRandom random = new SecureRandom();
    private final Duration defaultGrace;
    private final Duration defaultTurnTimeLimit;
    private final Duration emptyRoomTtl;

    private GameRoom room;  // null이면 방 없음
    // 방에 접속한 사람이 아무도 없게 된 시각 (접속자가 있으면 null)
    private Instant emptySince;
    // WebSocket 세션 ID → 입장한 playerId
    private final Map<String, String> sessions = new HashMap<>();

    public GameService(@Value("${game.disconnect-grace}") Duration defaultGrace,
                       @Value("${game.turn-time-limit}") Duration defaultTurnTimeLimit,
                       @Value("${game.empty-room-ttl}") Duration emptyRoomTtl) {
        this.defaultGrace = defaultGrace;
        this.defaultTurnTimeLimit = defaultTurnTimeLimit;
        this.emptyRoomTtl = emptyRoomTtl;
    }

    // 방을 새로 만들 수 있는지: 방이 없거나, 비어 있는 방의 방장 본인 (자기 방을 버리고 새로 시작)
    // 다른 사람의 빈 방은 유지 시간이 지나 삭제될 때까지 기다려야 함
    public synchronized boolean canCreateRoom(String playerId) {
        return room == null || (!room.hasConnectedPlayers() && room.isHost(playerId));
    }

    public synchronized String createRoom(String hostId) {
        if (!canCreateRoom(hostId)) {
            throw new GameException("이미 진행 중인 방이 있습니다. 초대 링크로 입장하세요.");
        }
        room = new GameRoom(newCode(), hostId, RoomSettings.defaults(defaultGrace, defaultTurnTimeLimit));
        emptySince = Instant.now();  // 방장이 들어오기 전까지는 빈 방
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
        emptySince = null;
        return target;
    }

    // 세션 종료 처리. 방이 남아 있으면 상태 갱신을 위해 반환
    public synchronized Optional<GameRoom> disconnect(String sessionId) {
        String playerId = sessions.remove(sessionId);
        if (playerId == null || room == null) return Optional.empty();

        // 같은 플레이어가 다른 탭(세션)으로 아직 접속 중이면 유지
        if (sessions.containsValue(playerId)) return Optional.of(room);

        room.disconnectPlayer(playerId);
        if (!room.hasConnectedPlayers() && emptySince == null) emptySince = Instant.now();
        return Optional.of(room);
    }

    // 빈 채로 유지 시간이 지난 방 삭제 (스케줄러가 주기적으로 호출). 삭제했으면 true
    public synchronized boolean expireEmptyRoom(Instant now) {
        if (room == null || emptySince == null || now.isBefore(emptySince.plus(emptyRoomTtl))) return false;
        if (room.hasConnectedPlayers()) {
            emptySince = null;
            return false;
        }
        room = null;
        emptySince = null;
        sessions.clear();
        return true;
    }

    private String newCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(CODE_CHARS.charAt(random.nextInt(CODE_CHARS.length())));
        }
        return sb.toString();
    }
}
