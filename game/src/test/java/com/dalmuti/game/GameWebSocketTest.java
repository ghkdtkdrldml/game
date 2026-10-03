package com.dalmuti.game;

import com.dalmuti.game.service.GameService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
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
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GameWebSocketTest {

    @LocalServerPort
    int port;

    @Autowired
    GameService gameService;

    // 리다이렉트를 따라가지 않아야 302 응답과 쿠키를 직접 확인할 수 있음 (기본값 NEVER)
    private final HttpClient http = HttpClient.newHttpClient();
    private final List<StompSession> sessions = new ArrayList<>();

    // 플레이어 한 명의 접속과 수신함
    class Client {
        final String cookie;
        final String code;
        final StompSession session;
        final BlockingQueue<Map<?, ?>> states = new LinkedBlockingQueue<>();
        final BlockingQueue<Map<?, ?>> privates = new LinkedBlockingQueue<>();
        final BlockingQueue<Map<?, ?>> errors = new LinkedBlockingQueue<>();


        Client(String cookie, String code) throws Exception {
            this.cookie = cookie;
            this.code = code;
            this.session = connect(cookie);
            sessions.add(session);

            subscribe("/topic/room/" + code, Map.class, states);
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

        void send(String action) {
            send(action, Map.of());
        }

        void send(String action, Object payload) {
            session.send("/app/room/" + code + "/" + action, payload);
        }

        void join() {
            send("join");
        }
    }

    // 새로 로그인한 사람으로 방에 연결
    private Client guest(String name, String code) throws Exception {
        return new Client(login(name), code);
    }

    // ---------- HTTP 도우미 ----------

    // 로그인 후 세션 쿠키 반환
    private String login(String name) throws Exception {
        HttpResponse<String> res = postLogin(name, null, null);
        assertEquals(302, res.statusCode());
        return res.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0];
    }

    private HttpResponse<String> postLogin(String name, String redirect, String cookie) throws Exception {
        String body = "name=" + URLEncoder.encode(name, StandardCharsets.UTF_8)
                + (redirect != null ? "&redirect=" + URLEncoder.encode(redirect, StandardCharsets.UTF_8) : "");
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url("/login")))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (cookie != null) req.header("Cookie", cookie);
        return http.send(req.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, String cookie) throws Exception {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url(path))).GET();
        if (cookie != null) req.header("Cookie", cookie);
        return http.send(req.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postCreateRoom(String cookie) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url("/rooms")))
                .header("Cookie", cookie)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    // 방 만들기 → 방 코드 반환
    private String createRoom(String cookie) throws Exception {
        String location = location(postCreateRoom(cookie));
        assertTrue(location.startsWith("/room/"), location);
        return location.substring("/room/".length());
    }

    private String location(HttpResponse<String> res) {
        assertEquals(302, res.statusCode());
        return URI.create(res.headers().firstValue("Location").orElseThrow()).getPath()
                + Optional.ofNullable(URI.create(res.headers().firstValue("Location").orElseThrow()).getQuery()).map(q -> "?" + q).orElse("");
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

    // 방이 하나뿐이라 테스트끼리 공유하므로, 끝나면 전원 퇴장해 방이 비워질 때까지 대기
    @AfterEach
    void disconnect() throws InterruptedException {
        sessions.stream().filter(StompSession::isConnected).forEach(StompSession::disconnect);
        long deadline = System.currentTimeMillis() + 5000;
        while (gameService.currentRoom().map(r -> r.hasConnectedPlayers()).orElse(false)) {
            if (System.currentTimeMillis() > deadline) fail("방이 비워지지 않았습니다.");
            Thread.sleep(50);
        }
        // 빈 방은 일정 시간 유지되므로, 다음 테스트가 새 방을 만들 수 있게 바로 삭제
        gameService.expireEmptyRoom(Instant.now().plus(Duration.ofDays(1)));
    }

    // ---------- 페이지 ----------

    @Test
    void pagesRequireLoginAndInviteLinkSurvivesLogin() throws Exception {
        assertEquals("/login", location(get("/", null)));
        assertEquals("/login?redirect=/room/abc123", location(get("/room/abc123", null)));

        // 로그인 후 초대 링크로 돌아감. 방 링크가 아닌 주소로는 보내지 않음 (오픈 리다이렉트 방지)
        assertEquals("/room/abc123", location(postLogin("링크손님", "/room/abc123", null)));
        assertEquals("/", location(postLogin("악성", "https://evil.example", null)));
    }

    @Test
    void homeShowsCreateButtonWhenNoRoom() throws Exception {
        String cookie = login("새손님");
        HttpResponse<String> res = get("/", cookie);
        assertEquals(200, res.statusCode());
        assertTrue(res.body().contains("action=\"/rooms\""));

        HttpResponse<String> kicked = get("/?error=kicked", cookie);
        assertEquals(200, kicked.statusCode());
        assertTrue(kicked.body().contains("방장에 의해 퇴장되었습니다."));
    }

    @Test
    void loginValidatesName() throws Exception {
        HttpResponse<String> res = postLogin("   ", null, null);
        assertEquals(200, res.statusCode());
        assertTrue(res.body().contains("이름은 1~12자로 입력하세요."));

        assertEquals(200, postLogin("1234567890123", null, null).statusCode());  // 13자
    }

    @Test
    void roomPageShowsEscapedNameAndConfig() throws Exception {
        String cookie = login("<b>홍길동</b>");
        String code = createRoom(cookie);
        HttpResponse<String> res = get("/room/" + code, cookie);
        assertEquals(200, res.statusCode());
        assertTrue(res.body().contains("&lt;b&gt;홍길동&lt;/b&gt;"));
        assertFalse(res.body().contains("<b>홍길동</b>"));
        assertTrue(res.body().contains("roomCode: \"" + code + "\""));
        // 카드 정보가 서버 enum에서 주입됨
        assertTrue(res.body().contains("\"DALMUTI\":{\"value\":1,\"name\":\"달무티\"}"), res.body());
    }

    @Test
    void unknownRoomRedirectsHome() throws Exception {
        String cookie = login("길잃음");
        assertEquals("/?error=roomNotFound", location(get("/room/nope1234", cookie)));
    }

    @Test
    void renameKeepsPlayerId() throws Exception {
        String cookie = login("처음이름");
        String code = createRoom(cookie);
        String before = extractPlayerId(get("/room/" + code, cookie).body());
        assertEquals(302, postLogin("바꾼이름", "/room/" + code, cookie).statusCode());
        String after = extractPlayerId(get("/room/" + code, cookie).body());
        assertEquals(before, after);
    }

    private String extractPlayerId(String html) {
        int start = html.indexOf("playerId: \"") + "playerId: \"".length();
        return html.substring(start, html.indexOf('"', start));
    }

    @Test
    void secondRoomCannotBeCreatedWhileInUse() throws Exception {
        String hostCookie = login("방장");
        Client host = new Client(hostCookie, createRoom(hostCookie));
        host.join();
        awaitMatching(host.states, s -> players(s).size() == 1);

        String other = login("다른사람");
        assertEquals("/?error=roomBusy", location(postCreateRoom(other)));
        // 참여 중인 사람에게는 홈에 돌아가기 링크
        assertTrue(get("/", host.cookie).body().contains("/room/" + host.code));
    }

    // ---------- 프록시 뒤 배포 (출처 검사) ----------

    private int sockJsInfoStatus(Map<String, String> headers) throws Exception {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url("/ws-dalmuti/info"))).GET();
        headers.forEach(req::header);
        return http.send(req.build(), HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    // 프록시 뒤에서는 브라우저 출처(https://도메인)와 서버가 받은 주소(http://내부주소)가 달라짐.
    // 프록시가 보내는 X-Forwarded-* 헤더를 반영해야 같은 출처로 인정되어 SockJS 연결(xhr_streaming, xhr_send 등)이 허용됨
    @Test
    void sockJsAcceptsSameOriginBehindProxy() throws Exception {
        String origin = "https://dalmuti.example.com";
        assertEquals(403, sockJsInfoStatus(Map.of("Origin", origin)));  // 헤더 없으면 다른 출처로 보고 거부
        assertEquals(200, sockJsInfoStatus(Map.of(
                "Origin", origin,
                "X-Forwarded-Host", "dalmuti.example.com",
                "X-Forwarded-Proto", "https")));
        // 전혀 다른 사이트에서의 연결은 계속 거부
        assertEquals(403, sockJsInfoStatus(Map.of(
                "Origin", "https://evil.example",
                "X-Forwarded-Host", "dalmuti.example.com",
                "X-Forwarded-Proto", "https")));
    }

    // ---------- WebSocket ----------

    @Test
    void webSocketRejectsWithoutLogin() {
        assertThrows(Exception.class, () -> connect(null));
    }

    @Test
    void handsArePrivateAndErrorsGoOnlyToSender() throws Exception {
        String hostCookie = login("A");
        String code = createRoom(hostCookie);
        Client a = new Client(hostCookie, code);
        Client b = guest("B", code);

        a.join();
        b.join();
        // 세션이 다르면 처리 순서가 보장되지 않으므로 두 명 입장을 확인한 뒤 시작
        Map<?, ?> lobby = awaitMatching(a.states, s -> players(s).size() == 2);
        assertEquals(Set.of("A", "B"), players(lobby).stream().map(p -> p.get("name")).collect(Collectors.toSet()));

        // 공개 상태에는 손패 장수만 있고 카드 목록은 없음
        Map<?, ?> state = startFirstRound(a, a, b);
        List<Map<?, ?>> players = players(state);
        assertEquals(2, players.size());
        for (Map<?, ?> player : players) {
            assertFalse(player.containsKey("hand"));
            assertEquals(40, player.get("handCount"));
        }

        // 각자 자기 손패 40장을 받음
        Map<?, ?> aPrivate = awaitMatching(a.privates, m -> ((List<?>) m.get("hand")).size() == 40);
        awaitMatching(b.privates, m -> ((List<?>) m.get("hand")).size() == 40);
        assertNull(aPrivate.get("tax"));  // 첫 판은 세금 없음

        // 입장 순서는 보장되지 않으므로 실제 선 플레이어를 확인
        int turn = (Integer) state.get("currentTurnIndex");
        boolean aHasTurn = "A".equals(players.get(turn).get("name"));
        Client onTurn = aHasTurn ? a : b;
        Client notOnTurn = aHasTurn ? b : a;

        // 턴이 아닌 플레이어가 패스 → 그 플레이어에게만 오류
        notOnTurn.send("pass");
        Map<?, ?> error = notOnTurn.errors.poll(5, TimeUnit.SECONDS);
        assertNotNull(error);
        assertEquals("내 턴이 아닙니다.", error.get("message"));
        assertNull(onTurn.errors.poll(500, TimeUnit.MILLISECONDS));
    }

    @Test
    void onlyHostCanStartAndHostCanKick() throws Exception {
        String hostCookie = login("방장");
        String code = createRoom(hostCookie);
        Client host = new Client(hostCookie, code);
        Client guest = guest("손님", code);
        host.join();
        awaitMatching(host.states, s -> players(s).size() == 1);
        guest.join();
        Map<?, ?> lobby = awaitMatching(host.states, s -> players(s).size() == 2);
        String guestId = players(lobby).stream().filter(p -> "손님".equals(p.get("name"))).findFirst().orElseThrow().get("id").toString();
        assertEquals(extractPlayerId(get("/room/" + code, hostCookie).body()), lobby.get("hostId"));

        guest.send("start");
        assertEquals("방장만 할 수 있습니다.", guest.errors.poll(5, TimeUnit.SECONDS).get("message"));

        host.send("kick", Map.of("playerId", guestId));
        Map<?, ?> kicked = guest.errors.poll(5, TimeUnit.SECONDS);
        assertNotNull(kicked);
        assertEquals("KICKED", kicked.get("code"));
        awaitMatching(host.states, s -> players(s).size() == 1);

        // 강퇴된 사람은 다시 들어올 수 없음
        guest.join();
        assertEquals("KICKED", guest.errors.poll(5, TimeUnit.SECONDS).get("code"));
    }

    @Test
    void wrongRoomCodeIsRejectedOverWebSocket() throws Exception {
        String code = createRoom(login("방장2"));
        Client stranger = guest("낯선사람", code + "x");
        stranger.join();
        Map<?, ?> error = stranger.errors.poll(5, TimeUnit.SECONDS);
        assertNotNull(error);
        assertEquals("ROOM_NOT_FOUND", error.get("code"));
    }

    @Test
    void lobbyDisconnectIsBroadcast() throws Exception {
        String hostCookie = login("A");
        String code = createRoom(hostCookie);
        Client a = new Client(hostCookie, code);
        Client b = guest("B", code);

        a.join();
        b.join();
        awaitMatching(a.states, s -> players(s).size() == 2);

        b.session.disconnect();
        awaitMatching(a.states, s -> players(s).size() == 1);
    }

    @Test
    void loginRejectsNameAlreadyInRoom() throws Exception {
        String hostCookie = login("홍길동");
        Client a = new Client(hostCookie, createRoom(hostCookie));
        a.join();
        awaitMatching(a.states, s -> players(s).size() == 1);

        HttpResponse<String> res = postLogin(" 홍길동 ", null, null);
        assertEquals(200, res.statusCode());
        assertTrue(res.body().contains("이미 사용 중인 이름입니다"));
    }

    // 둘 다 입장 전에 같은 이름으로 로그인한 경우: 나중에 입장한 쪽이 NAME_TAKEN 오류를 받음
    @Test
    void joinRejectsDuplicateNameRace() throws Exception {
        String firstCookie = login("철수");
        String code = createRoom(firstCookie);
        Client first = new Client(firstCookie, code);
        Client second = guest("철수", code);

        first.join();
        awaitMatching(first.states, s -> players(s).size() == 1);
        second.join();

        Map<?, ?> error = second.errors.poll(5, TimeUnit.SECONDS);
        assertNotNull(error);
        assertEquals("NAME_TAKEN", error.get("code"));
    }

    @Test
    void joiningDuringGameWaitsAndSpectates() throws Exception {
        String hostCookie = login("A");
        String code = createRoom(hostCookie);
        Client a = new Client(hostCookie, code);
        Client b = guest("B", code);
        a.join();
        b.join();
        awaitMatching(a.states, s -> players(s).size() == 2);
        a.send("start");
        awaitMatching(a.states, s -> Boolean.TRUE.equals(s.get("gameStarted")));

        Client c = guest("C", code);
        c.join();
        // 관전자도 방 상태를 받고, 대기 목록에 표시됨
        Map<?, ?> state = awaitMatching(c.states, s -> ((List<?>) s.get("waitingPlayers")).size() == 1);
        assertEquals(2, players(state).size());
        assertTrue((Boolean) state.get("gameStarted"));
        // 개인 상태도 받지만 손패는 없음
        Map<?, ?> cPrivate = awaitMatching(c.privates, m -> true);
        assertTrue(((List<?>) cPrivate.get("hand")).isEmpty());
    }

    // 게임 중 새로고침: 같은 로그인(쿠키)으로 다시 연결하면 같은 자리·손패로 복귀
    @Test
    void refreshDuringGameRestoresPlayer() throws Exception {
        String cookieA = login("A");
        String code = createRoom(cookieA);
        Client a = new Client(cookieA, code);
        Client b = guest("B", code);

        a.join();
        b.join();
        awaitMatching(b.states, s -> players(s).size() == 2);
        startFirstRound(a, a, b);
        List<?> handBefore = (List<?>) awaitMatching(a.privates, m -> !((List<?>) m.get("hand")).isEmpty()).get("hand");

        // 새로고침 = 연결 끊김 → 다른 플레이어에게 연결 끊김으로 표시
        a.session.disconnect();
        awaitMatching(b.states, s -> players(s).stream()
                .anyMatch(p -> "A".equals(p.get("name")) && Boolean.FALSE.equals(p.get("connected"))));

        // 같은 쿠키로 재연결 후 입장
        Client aAgain = new Client(cookieA, code);
        aAgain.join();
        Map<?, ?> restored = awaitMatching(aAgain.privates, m -> !((List<?>) m.get("hand")).isEmpty());
        assertEquals(handBefore, restored.get("hand"));
        Map<?, ?> state = awaitMatching(b.states, s -> players(s).stream()
                .allMatch(p -> Boolean.TRUE.equals(p.get("connected"))));
        assertEquals(2, players(state).size());
        assertTrue((Boolean) state.get("gameStarted"));
    }

    // ---------- 도우미 ----------

    @SuppressWarnings("unchecked")
    private static List<Map<?, ?>> players(Map<?, ?> state) {
        return (List<Map<?, ?>>) state.get("players");
    }

    // 첫 판 시작(방장): 자리 뽑기 후 전원이 카드를 뽑아 배분까지 끝난 상태를 반환
    private Map<?, ?> startFirstRound(Client host, Client... all) throws InterruptedException {
        host.send("start");
        for (Client c : all) {
            awaitMatching(c.states, s -> Boolean.TRUE.equals(s.get("seatDrawPhase")));
            c.send("draw");
        }
        return awaitMatching(host.states,
                s -> Boolean.TRUE.equals(s.get("gameStarted")) && Boolean.FALSE.equals(s.get("seatDrawPhase")));
    }

    private static <T> T awaitMatching(BlockingQueue<T> queue, Predicate<T> condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            T item = queue.poll(deadline - System.currentTimeMillis(), TimeUnit.MILLISECONDS);
            if (item != null && condition.test(item)) return item;
        }
        return fail("조건에 맞는 메시지를 받지 못했습니다.");
    }
}
