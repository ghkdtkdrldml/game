// controller/GameController.java
package com.dalmuti.game.controller;

import com.dalmuti.game.auth.PlayerPrincipal;
import com.dalmuti.game.dto.*;
import com.dalmuti.game.exception.GameException;
import com.dalmuti.game.model.GameRoom;
import com.dalmuti.game.model.Player;
import com.dalmuti.game.model.RoomSettings;
import com.dalmuti.game.service.GameService;
import lombok.RequiredArgsConstructor;
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
import java.util.function.BiConsumer;

// 방 코드가 경로에 들어가므로 초대 링크(코드)를 모르면 방 상태를 구독하거나 조작할 수 없음
@Controller
@RequiredArgsConstructor
public class GameController {

    private final GameService gameService;
    private final SimpMessagingTemplate messagingTemplate;

    @MessageMapping("/room/{code}/join")
    public void joinRoom(@DestinationVariable String code, Principal principal, SimpMessageHeaderAccessor headerAccessor) {
        requirePlayerId(principal);
        // 이름은 요청 본문이 아니라 로그인 시 정한 값을 사용
        PlayerPrincipal player = (PlayerPrincipal) principal;
        GameRoom room = gameService.join(code, new Player(player.playerId(), player.playerName()), headerAccessor.getSessionId());
        // 상태 직렬화(전송)를 방 락 안에서 처리해 중간 상태가 전송되지 않도록 함
        synchronized (room) {
            broadcast(room);
        }
    }

    @MessageMapping("/room/{code}/start")
    public void startGame(@DestinationVariable String code, Principal principal) {
        act(code, principal, (room, playerId) -> room.startGame(playerId));
    }

    @MessageMapping("/room/{code}/draw")
    public void drawSeatCard(@DestinationVariable String code, Principal principal) {
        act(code, principal, (room, playerId) -> room.drawSeatCard(playerId));
    }

    @MessageMapping("/room/{code}/revolution")
    public void decideRevolution(@DestinationVariable String code, RevolutionRequest request, Principal principal) {
        act(code, principal, (room, playerId) -> room.decideRevolution(playerId, request.declare()));
    }

    @MessageMapping("/room/{code}/tax")
    public void returnTax(@DestinationVariable String code, ActionRequest request, Principal principal) {
        act(code, principal, (room, playerId) -> room.returnTax(playerId, request.getCards()));
    }

    @MessageMapping("/room/{code}/play")
    public void playCards(@DestinationVariable String code, ActionRequest request, Principal principal) {
        act(code, principal, (room, playerId) -> room.playCards(playerId, request.getCards()));
    }

    @MessageMapping("/room/{code}/pass")
    public void passTurn(@DestinationVariable String code, Principal principal) {
        act(code, principal, (room, playerId) -> room.pass(playerId));
    }

    // ---------- 방장 전용 ----------

    @MessageMapping("/room/{code}/kick")
    public void kick(@DestinationVariable String code, TargetRequest request, Principal principal) {
        act(code, principal, (room, playerId) -> {
            room.kick(playerId, request.playerId());
            // 강퇴된 사람에게 알려 화면을 나가게 함
            messagingTemplate.convertAndSendToUser(request.playerId(), "/queue/errors",
                    new ErrorMessage(GameException.KICKED, "방장에 의해 퇴장되었습니다."));
        });
    }

    @MessageMapping("/room/{code}/transfer-host")
    public void transferHost(@DestinationVariable String code, TargetRequest request, Principal principal) {
        act(code, principal, (room, playerId) -> room.transferHost(playerId, request.playerId()));
    }

    @MessageMapping("/room/{code}/abort")
    public void abortRound(@DestinationVariable String code, Principal principal) {
        act(code, principal, (room, playerId) -> room.abortRound(playerId));
    }

    @MessageMapping("/room/{code}/settings")
    public void updateSettings(@DestinationVariable String code, SettingsRequest request, Principal principal) {
        act(code, principal, (room, playerId) -> room.updateSettings(playerId, new RoomSettings(
                Duration.ofSeconds(request.disconnectGraceSeconds()), request.maxPlayers(), request.allowLateJoin())));
    }

    // 방을 찾아 방 락 안에서 처리하고 상태를 전송
    private void act(String code, Principal principal, BiConsumer<GameRoom, String> action) {
        String playerId = requirePlayerId(principal);
        GameRoom room = gameService.getRoom(code);
        synchronized (room) {
            action.accept(room, playerId);
            broadcast(room);
        }
    }

    // 연결이 끊긴 플레이어 차례에 게임이 멈추지 않도록 대신 진행하고, 방장 부재 시 다음 사람에게 넘김
    // (방 설정의 유예 시간이 지난 뒤)
    @Scheduled(fixedDelay = 1000)
    public void tick() {
        gameService.currentRoom().ifPresent(room -> {
            synchronized (room) {
                Instant now = Instant.now();
                Duration grace = room.getSettings().disconnectGrace();
                boolean changed = room.actForAwayPlayers(now, grace);
                changed |= room.updateHost(now, grace);
                if (changed) broadcast(room);
            }
        });
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
        messagingTemplate.convertAndSend("/topic/room/" + room.getCode(), RoomState.from(room));
        for (Player p : room.allMembers()) {
            messagingTemplate.convertAndSendToUser(p.getId(), "/queue/private", PrivateState.of(room, p));
        }
    }
}
