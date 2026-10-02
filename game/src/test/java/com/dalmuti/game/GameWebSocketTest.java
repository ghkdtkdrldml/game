package com.dalmuti.game;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import com.dalmuti.game.service.GameService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.springframework.web.socket.sockjs.client.WebSocketTransport;

import java.lang.reflect.Type;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GameWebSocketTest {

    @LocalServerPort
    int port;

    // 리다이렉트를 따라가지 않아야 302 응답과 쿠키를 직접 확인할 수 있음 (기본값 NEVER)
    private final HttpClient http = HttpClient.newHttpClient();
    private final List<StompSession> sessions = new ArrayList<>();

    // 플레이어 한 명의 접속과 수신함
    class Client {
        final StompSession session;
        final BlockingQueue<Map<?, ?>> states = new LinkedBlockingQueue<>();
        final BlockingQueue<Map<?, ?>> privates = new LinkedBlockingQueue<>();
        final BlockingQueue<Map<?, ?>> errors = new LinkedBlockingQueue<>();

        Client(String name) throws Exception {
            this(connect(login(name)));
        }

        Client(StompSession session) {
            this.session = session;
            sessions.add(session);

            subscribe("/topic/room", Map.class, states);
            subscribe("/user/queue/private", Map.class, privates);
            subscribe("/user/queue/errors", Map.class, errors);
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private void subscribe(String destination, Class<?> type, BlockingQueue queue) {
            session.subscribe(destination, new StompFrameHandler() {
                @Override
                public Type getPayloadType(StompHeaders headers) {
                    return type;
                }

                @Override
                public void handleFrame(StompHeaders headers, Object payload) {
                    queue.add(payload);
                }
            });
        }

        void send(String destination) {
            session.send(destination, Map.of());
        }
    }

    // 로그인 후 세션 쿠키 반환
    private String login(String name) throws Exception {
        HttpResponse<String> res = postLogin(name, null);
        assertEquals(302, res.statusCode());
        return res.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0];
    }

    private HttpResponse<String> postLogin(String name, String cookie) throws Exception {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url("/login")))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("name=" + URLEncoder.encode(name, StandardCharsets.UTF_8)));
        if (cookie != null) req.header("Cookie", cookie);
        return http.send(req.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, String cookie) throws Exception {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url(path))).GET();
        if (cookie != null) req.header("Cookie", cookie);
        return http.send(req.build(), HttpResponse.BodyHandlers.ofString());
    }

    private StompSession connect(String cookie) throws Exception {
        WebSocketStompClient stomp = new WebSocketStompClient(
                new SockJsClient(List.of(new WebSocketTransport(new StandardWebSocketClient()))));
        stomp.setMessageConverter(new JacksonJsonMessageConverter());

        WebSocketHttpHeaders handshakeHeaders = new WebSocketHttpHeaders();
        if (cookie != null) handshakeHeaders.add("Cookie", cookie);
        return stomp.connectAsync(url("/ws-dalmuti"), handshakeHeaders, new StompHeaders(), new StompSessionHandlerAdapter() {
        }).get(5, TimeUnit.SECONDS);
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @Autowired
    GameService gameService;

    // 방이 하나뿐이라 테스트끼리 공유하므로, 끝나면 전원 퇴장해 방이 초기화될 때까지 대기
    @AfterEach
    void disconnect() throws InterruptedException {
        sessions.stream().filter(StompSession::isConnected).forEach(StompSession::disconnect);
        long deadline = System.currentTimeMillis() + 5000;
        while (!gameService.getRoom().getPlayers().isEmpty()) {
            if (System.currentTimeMillis() > deadline) fail("방이 초기화되지 않았습니다.");
            Thread.sleep(50);
        }
    }

    @Test
    void gamePageRequiresLogin() throws Exception {
        HttpResponse<String> res = get("/game", null);
        assertEquals(302, res.statusCode());
        assertTrue(res.headers().firstValue("Location").orElseThrow().endsWith("/login"));
    }

    @Test
    void loginValidatesName() throws Exception {
        HttpResponse<String> res = postLogin("   ", null);
        assertEquals(200, res.statusCode());
        assertTrue(res.body().contains("이름은 1~12자로 입력하세요."));

        assertEquals(200, postLogin("1234567890123", null).statusCode());  // 13자
    }

    @Test
    void gamePageShowsEscapedNameAndConfig() throws Exception {
        String cookie = login("<b>홍길동</b>");
        HttpResponse<String> res = get("/game", cookie);
        assertEquals(200, res.statusCode());
        assertTrue(res.body().contains("&lt;b&gt;홍길동&lt;/b&gt;"));
        assertFalse(res.body().contains("<b>홍길동</b>"));
        // 카드 정보가 서버 enum에서 주입됨
        assertTrue(res.body().contains("\"DALMUTI\":{\"value\":1,\"name\":\"달무티\"}"), res.body());
    }

    @Test
    void renameKeepsPlayerId() throws Exception {
        String cookie = login("처음이름");
        String before = extractPlayerId(get("/game", cookie).body());
        assertEquals(302, postLogin("바꾼이름", cookie).statusCode());
        String after = extractPlayerId(get("/game", cookie).body());
        assertEquals(before, after);
    }

    private String extractPlayerId(String html) {
        int start = html.indexOf("playerId: \"") + "playerId: \"".length();
        return html.substring(start, html.indexOf('"', start));
    }

    @Test
    void webSocketRejectsWithoutLogin() {
        assertThrows(Exception.class, () -> connect(null));
    }

    @Test
    void handsArePrivateAndErrorsGoOnlyToSender() throws Exception {
        Client a = new Client("A");
        Client b = new Client("B");

        a.send("/app/game/join");
        b.send("/app/game/join");
        // 세션이 다르면 처리 순서가 보장되지 않으므로 두 명 입장을 확인한 뒤 시작
        Map<?, ?> lobby = awaitMatching(a.states, s -> ((List<?>) s.get("players")).size() == 2);
        // 이름은 로그인 시 정한 값
        assertEquals(Set.of("A", "B"), ((List<?>) lobby.get("players")).stream()
                .map(p -> ((Map<?, ?>) p).get("name")).collect(java.util.stream.Collectors.toSet()));
        a.send("/app/game/start");

        // 공개 상태에는 손패 장수만 있고 카드 목록은 없음
        Map<?, ?> state = awaitMatching(a.states, s -> Boolean.TRUE.equals(s.get("gameStarted")));
        List<?> players = (List<?>) state.get("players");
        assertEquals(2, players.size());
        for (Object p : players) {
            Map<?, ?> player = (Map<?, ?>) p;
            assertFalse(player.containsKey("hand"));
            assertEquals(40, player.get("handCount"));
        }

        // 각자 자기 손패 40장을 받음
        Map<?, ?> aPrivate = awaitMatching(a.privates, m -> ((List<?>) m.get("hand")).size() == 40);
        awaitMatching(b.privates, m -> ((List<?>) m.get("hand")).size() == 40);
        assertNull(aPrivate.get("tax"));  // 첫 판은 세금 없음

        // 입장 순서는 보장되지 않으므로 실제 선 플레이어를 확인
        int turn = (Integer) state.get("currentTurnIndex");
        boolean aHasTurn = "A".equals(((Map<?, ?>) players.get(turn)).get("name"));
        Client onTurn = aHasTurn ? a : b;
        Client notOnTurn = aHasTurn ? b : a;

        // 턴이 아닌 플레이어가 패스 → 그 플레이어에게만 오류
        notOnTurn.send("/app/game/pass");
        Map<?, ?> error = notOnTurn.errors.poll(5, TimeUnit.SECONDS);
        assertNotNull(error);
        assertEquals("내 턴이 아닙니다.", error.get("message"));
        assertNull(onTurn.errors.poll(500, TimeUnit.MILLISECONDS));
    }

    @Test
    void lobbyDisconnectIsBroadcast() throws Exception {
        Client a = new Client("A");
        Client b = new Client("B");

        a.send("/app/game/join");
        b.send("/app/game/join");
        awaitMatching(a.states, s -> ((List<?>) s.get("players")).size() == 2);

        b.session.disconnect();
        awaitMatching(a.states, s -> ((List<?>) s.get("players")).size() == 1);
    }

    // 게임 중 새로고침: 같은 로그인(쿠키)으로 다시 연결하면 같은 자리·손패로 복귀
    @Test
    void refreshDuringGameRestoresPlayer() throws Exception {
        String cookieA = login("A");
        Client a = new Client(connect(cookieA));
        Client b = new Client("B");

        a.send("/app/game/join");
        b.send("/app/game/join");
        awaitMatching(b.states, s -> ((List<?>) s.get("players")).size() == 2);
        b.send("/app/game/start");
        List<?> handBefore = (List<?>) awaitMatching(a.privates, m -> !((List<?>) m.get("hand")).isEmpty()).get("hand");

        // 새로고침 = 연결 끊김 → 다른 플레이어에게 연결 끊김으로 표시
        a.session.disconnect();
        awaitMatching(b.states, s -> ((List<?>) s.get("players")).stream()
                .anyMatch(p -> "A".equals(((Map<?, ?>) p).get("name")) && Boolean.FALSE.equals(((Map<?, ?>) p).get("connected"))));

        // 같은 쿠키로 재연결 후 입장
        Client aAgain = new Client(connect(cookieA));
        aAgain.send("/app/game/join");
        Map<?, ?> restored = awaitMatching(aAgain.privates, m -> !((List<?>) m.get("hand")).isEmpty());
        assertEquals(handBefore, restored.get("hand"));
        Map<?, ?> state = awaitMatching(b.states, s -> ((List<?>) s.get("players")).stream()
                .allMatch(p -> Boolean.TRUE.equals(((Map<?, ?>) p).get("connected"))));
        assertEquals(2, ((List<?>) state.get("players")).size());
        assertTrue((Boolean) state.get("gameStarted"));
    }

    private static <T> T awaitMatching(BlockingQueue<T> queue, java.util.function.Predicate<T> condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            T item = queue.poll(deadline - System.currentTimeMillis(), TimeUnit.MILLISECONDS);
            if (item != null && condition.test(item)) return item;
        }
        return fail("조건에 맞는 메시지를 받지 못했습니다.");
    }
}
