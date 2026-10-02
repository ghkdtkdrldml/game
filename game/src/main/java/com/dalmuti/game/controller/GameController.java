// controller/GameController.java
package com.dalmuti.game.controller;

import com.dalmuti.game.auth.PlayerPrincipal;
import com.dalmuti.game.dto.ActionRequest;
import com.dalmuti.game.dto.ErrorMessage;
import com.dalmuti.game.dto.PrivateState;
import com.dalmuti.game.dto.RevolutionRequest;
import com.dalmuti.game.dto.RoomState;
import com.dalmuti.game.exception.GameException;
import com.dalmuti.game.model.GameRoom;
import com.dalmuti.game.model.Player;
import com.dalmuti.game.service.GameService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.handler.annotation.*;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Controller;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.security.Principal;
import java.time.Duration;
import java.time.Instant;

// 방은 하나뿐이므로 경로에 방 ID 없음
@Controller
@RequiredArgsConstructor
public class GameController {

    private static final String ROOM_TOPIC = "/topic/room";

    private final GameService gameService;
    private final SimpMessagingTemplate messagingTemplate;

    // 연결이 끊긴 플레이어를 기다려주는 시간. 지나면 자동 패스 등으로 대신 진행 (application.yaml)
    @Value("${game.disconnect-grace}")
    private Duration disconnectGrace;

    @MessageMapping("/game/join")
    public void joinRoom(Principal principal, SimpMessageHeaderAccessor headerAccessor) {
        requirePlayerId(principal);
        // 이름은 요청 본문이 아니라 로그인 시 정한 값을 사용
        PlayerPrincipal player = (PlayerPrincipal) principal;
        GameRoom room = gameService.join(new Player(player.playerId(), player.playerName()), headerAccessor.getSessionId());
        // 상태 직렬화(전송)를 방 락 안에서 처리해 중간 상태가 전송되지 않도록 함
        synchronized (room) {
            broadcast(room);
        }
    }

    @MessageMapping("/game/start")
    public void startGame(Principal principal) {
        String playerId = requirePlayerId(principal);
        GameRoom room = gameService.getRoom();
        synchronized (room) {
            room.startGame(playerId);
            broadcast(room);
        }
    }

    @MessageMapping("/game/draw")
    public void drawSeatCard(Principal principal) {
        String playerId = requirePlayerId(principal);
        GameRoom room = gameService.getRoom();
        synchronized (room) {
            room.drawSeatCard(playerId);
            broadcast(room);
        }
    }

    @MessageMapping("/game/revolution")
    public void decideRevolution(RevolutionRequest request, Principal principal) {
        String playerId = requirePlayerId(principal);
        GameRoom room = gameService.getRoom();
        synchronized (room) {
            room.decideRevolution(playerId, request.declare());
            broadcast(room);
        }
    }

    @MessageMapping("/game/tax")
    public void returnTax(ActionRequest request, Principal principal) {
        String playerId = requirePlayerId(principal);
        GameRoom room = gameService.getRoom();
        synchronized (room) {
            room.returnTax(playerId, request.getCards());
            broadcast(room);
        }
    }

    @MessageMapping("/game/play")
    public void playCards(ActionRequest request, Principal principal) {
        String playerId = requirePlayerId(principal);
        GameRoom room = gameService.getRoom();
        synchronized (room) {
            room.playCards(playerId, request.getCards());
            broadcast(room);
        }
    }

    @MessageMapping("/game/pass")
    public void passTurn(Principal principal) {
        String playerId = requirePlayerId(principal);
        GameRoom room = gameService.getRoom();
        synchronized (room) {
            room.pass(playerId);
            broadcast(room);
        }
    }

    // 연결이 끊긴 플레이어 차례에 게임이 멈추지 않도록 주기적으로 대신 진행 (새로고침 등을 고려해 유예 시간 후)
    @Scheduled(fixedDelay = 1000)
    public void actForAwayPlayers() {
        GameRoom room = gameService.getRoom();
        synchronized (room) {
            if (room.actForAwayPlayers(Instant.now(), disconnectGrace)) {
                broadcast(room);
            }
        }
    }

    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        gameService.disconnect(event.getSessionId()).ifPresent(room -> {
            synchronized (room) {
                broadcast(room);
            }
        });
    }

    // 요청을 보낸 세션에만 오류 사유 전달
    @MessageExceptionHandler(GameException.class)
    @SendToUser(destinations = "/queue/errors", broadcast = false)
    public ErrorMessage handleGameException(GameException e) {
        return new ErrorMessage(e.getCode(), e.getMessage());
    }

    private String requirePlayerId(Principal principal) {
        if (principal == null) throw new GameException("플레이어 식별 정보가 없습니다. 다시 접속하세요.");
        return principal.getName();
    }

    // 공개 상태는 방 전체에, 손패·세금 내역은 각 플레이어(대기자 포함)에게만 전송
    private void broadcast(GameRoom room) {
        messagingTemplate.convertAndSend(ROOM_TOPIC, RoomState.from(room));
        for (Player p : room.allMembers()) {
            messagingTemplate.convertAndSendToUser(p.getId(), "/queue/private", PrivateState.of(room, p));
        }
    }
}
