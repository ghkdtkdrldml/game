package com.dalmuti.game.controller;

import com.dalmuti.game.auth.PlayerPrincipal;
import com.dalmuti.game.exception.GameException;
import com.dalmuti.game.model.CardType;
import com.dalmuti.game.model.Rank;
import com.dalmuti.game.service.GameService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

// 흐름: 로그인(이름) → 홈(방 만들기 / 참여 중인 방으로) → /room/{code} (초대 링크)
// 초대 링크로 들어왔는데 로그인 전이면 로그인 후 그 링크로 돌아감
@Controller
@RequiredArgsConstructor
public class PageController {

    private static final int MAX_NAME_LENGTH = 12;
    private static final String NAME_TAKEN_MESSAGE = "이미 사용 중인 이름입니다. 다른 이름을 입력하세요.";
    // 로그인 후 돌아갈 주소는 방 링크만 허용 (외부 주소로 보내는 오픈 리다이렉트 방지)
    private static final Pattern ROOM_PATH = Pattern.compile("^/room/[A-Za-z0-9]{1,16}$");

    private static final Map<String, String> HOME_MESSAGES = Map.of(
            "roomNotFound", "방이 없습니다. 링크를 확인하거나 새 방을 만드세요.",
            "roomBusy", "이미 진행 중인 방이 있습니다. 초대 링크를 받아 입장하세요.",
            "kicked", "방장에 의해 퇴장되었습니다.");

    private final GameService gameService;

    // 페이지 조회는 @SessionAttribute로 읽어, 로그인하지 않은 방문에 불필요한 세션을 만들지 않음
    @GetMapping("/")
    public String home(@SessionAttribute(name = PlayerPrincipal.SESSION_KEY, required = false) PlayerPrincipal player,
                       @RequestParam(required = false) String error, Model model) {
        if (player == null) return "redirect:/login";

        model.addAttribute("playerName", player.playerName());
        model.addAttribute("error", error != null ? HOME_MESSAGES.get(error) : null);
        // 지금 있는 방: 내가 참여 중이면 돌아가기, 다른 사람들 방이면 만들기 불가 안내
        // (템플릿 조건식에서 null이 boolean으로 변환되지 않도록 기본값 지정)
        // 락 순서(GameService → GameRoom)를 지키기 위해 방 락을 잡기 전에 확인
        boolean canCreate = gameService.canCreateRoom(player.playerId());
        model.addAttribute("canCreate", canCreate);
        model.addAttribute("roomBusy", false);
        gameService.currentRoom().ifPresent(room -> {
            synchronized (room) {
                // 참여 중이거나, 비어 있는 동안 유지 중인 내 방(방장)이면 돌아가기
                boolean member = room.allMembers().stream().anyMatch(p -> p.getId().equals(player.playerId()));
                if (member || room.isHost(player.playerId())) model.addAttribute("myRoomCode", room.getCode());
                else if (!canCreate) model.addAttribute("roomBusy", true);
            }
        });
        return "home";
    }

    @PostMapping("/rooms")
    public String createRoom(@SessionAttribute(name = PlayerPrincipal.SESSION_KEY, required = false) PlayerPrincipal player) {
        if (player == null) return "redirect:/login";
        try {
            return "redirect:/room/" + gameService.createRoom(player.playerId());
        } catch (GameException e) {
            return "redirect:/?error=roomBusy";
        }
    }

    @GetMapping("/room/{code}")
    public String room(@PathVariable String code,
                       @SessionAttribute(name = PlayerPrincipal.SESSION_KEY, required = false) PlayerPrincipal player,
                       Model model) {
        if (player == null) return "redirect:/login?redirect=/room/" + code;
        if (gameService.currentRoom().filter(r -> r.getCode().equals(code)).isEmpty()) {
            return "redirect:/?error=roomNotFound";
        }

        model.addAttribute("roomCode", code);
        model.addAttribute("playerId", player.playerId());
        model.addAttribute("playerName", player.playerName());
        model.addAttribute("cards", cards());
        model.addAttribute("rankNames", rankNames());
        return "game";
    }

    // nameTaken: 게임 화면에서 입장하다 이름 중복으로 돌아온 경우
    @GetMapping("/login")
    public String loginPage(@SessionAttribute(name = PlayerPrincipal.SESSION_KEY, required = false) PlayerPrincipal player,
                            @RequestParam(required = false) String error,
                            @RequestParam(required = false) String redirect, Model model) {
        if (player != null) model.addAttribute("name", player.playerName());
        if ("nameTaken".equals(error)) model.addAttribute("error", NAME_TAKEN_MESSAGE);
        model.addAttribute("redirect", safeRedirect(redirect));
        return "login";
    }

    @PostMapping("/login")
    public String login(@RequestParam(defaultValue = "") String name, @RequestParam(required = false) String redirect,
                        HttpSession session, Model model) {
        String trimmed = name.strip();
        String target = safeRedirect(redirect);
        model.addAttribute("name", trimmed);
        model.addAttribute("redirect", target);
        if (trimmed.isEmpty() || trimmed.length() > MAX_NAME_LENGTH) {
            model.addAttribute("error", "이름은 1~" + MAX_NAME_LENGTH + "자로 입력하세요.");
            return "login";
        }

        // 이미 로그인한 상태에서 이름만 바꾸면 같은 playerId를 유지 (진행 중인 방에 재접속 가능)
        PlayerPrincipal current = currentPlayer(session);
        if (gameService.isNameTaken(trimmed, current != null ? current.playerId() : null)) {
            model.addAttribute("error", NAME_TAKEN_MESSAGE);
            return "login";
        }
        String playerId = current != null ? current.playerId() : UUID.randomUUID().toString();
        session.setAttribute(PlayerPrincipal.SESSION_KEY, new PlayerPrincipal(playerId, trimmed));
        return "redirect:" + target;
    }

    @PostMapping("/logout")
    public String logout(HttpSession session) {
        session.invalidate();
        return "redirect:/login";
    }

    private String safeRedirect(String redirect) {
        return redirect != null && ROOM_PATH.matcher(redirect).matches() ? redirect : "/";
    }

    private PlayerPrincipal currentPlayer(HttpSession session) {
        return session.getAttribute(PlayerPrincipal.SESSION_KEY) instanceof PlayerPrincipal p ? p : null;
    }

    // 화면 표시용 카드 정보(숫자, 이름)를 서버 enum에서 만들어 전달 (JS에 중복 정의하지 않도록)
    private Map<String, Map<String, Object>> cards() {
        Map<String, Map<String, Object>> cards = new LinkedHashMap<>();
        for (CardType c : CardType.values()) {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("value", c.getValue());
            info.put("name", c.getKorName());
            cards.put(c.name(), info);
        }
        return cards;
    }

    private Map<String, String> rankNames() {
        Map<String, String> names = new LinkedHashMap<>();
        for (Rank r : Rank.values()) {
            names.put(r.name(), r.getTitle());
        }
        return names;
    }
}
