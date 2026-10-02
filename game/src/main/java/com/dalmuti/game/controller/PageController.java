package com.dalmuti.game.controller;

import com.dalmuti.game.auth.PlayerPrincipal;
import com.dalmuti.game.model.CardType;
import com.dalmuti.game.model.Rank;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.SessionAttribute;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Controller
public class PageController {

    private static final int MAX_NAME_LENGTH = 12;

    // 페이지 조회는 @SessionAttribute로 읽어, 로그인하지 않은 방문에 불필요한 세션을 만들지 않음
    @GetMapping("/")
    public String home(@SessionAttribute(name = PlayerPrincipal.SESSION_KEY, required = false) PlayerPrincipal player) {
        return player != null ? "redirect:/game" : "redirect:/login";
    }

    @GetMapping("/login")
    public String loginPage(@SessionAttribute(name = PlayerPrincipal.SESSION_KEY, required = false) PlayerPrincipal player,
                            Model model) {
        if (player != null) model.addAttribute("name", player.playerName());
        return "login";
    }

    @PostMapping("/login")
    public String login(@RequestParam(defaultValue = "") String name, HttpSession session, Model model) {
        String trimmed = name.strip();
        if (trimmed.isEmpty() || trimmed.length() > MAX_NAME_LENGTH) {
            model.addAttribute("name", trimmed);
            model.addAttribute("error", "이름은 1~" + MAX_NAME_LENGTH + "자로 입력하세요.");
            return "login";
        }

        // 이미 로그인한 상태에서 이름만 바꾸면 같은 playerId를 유지 (진행 중인 방에 재접속 가능)
        PlayerPrincipal current = currentPlayer(session);
        String playerId = current != null ? current.playerId() : UUID.randomUUID().toString();
        session.setAttribute(PlayerPrincipal.SESSION_KEY, new PlayerPrincipal(playerId, trimmed));
        return "redirect:/game";
    }

    @PostMapping("/logout")
    public String logout(HttpSession session) {
        session.invalidate();
        return "redirect:/login";
    }

    @GetMapping("/game")
    public String game(@SessionAttribute(name = PlayerPrincipal.SESSION_KEY, required = false) PlayerPrincipal player,
                       Model model) {
        if (player == null) return "redirect:/login";

        model.addAttribute("playerId", player.playerId());
        model.addAttribute("playerName", player.playerName());
        model.addAttribute("cards", cards());
        model.addAttribute("rankNames", rankNames());
        return "game";
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
