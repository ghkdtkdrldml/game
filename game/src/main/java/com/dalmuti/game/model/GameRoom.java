package com.dalmuti.game.model;

import com.dalmuti.game.exception.GameException;
import lombok.Getter;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Getter
public class GameRoom {
    private static final int MIN_PLAYERS = 2;
    private static final int MAX_PLAYERS = 10;

    private final List<Player> players = new ArrayList<>();
    // 게임 도중 들어와 다음 판을 기다리는 사람 (관전). 판이 끝나면 players로 합류
    private final List<Player> waitingPlayers = new ArrayList<>();

    private int currentTurnIndex = 0;
    private CardType currentTrickType = null;
    private int currentTrickCount = 0;
    // 현재 바닥 카드를 낸 플레이어. 턴이 이 플레이어에게 돌아오면 나머지 전원이 패스한 것
    private int lastPlayerIndex = -1;
    private boolean isGameStarted = false;
    private boolean gameOver = false;
    // 손패를 모두 낸 순서 (playerId)
    private final List<String> finishOrder = new ArrayList<>();

    // 이번 판 세금 교환 기록. 반환 대기 중인 교환이 있으면 세금 교환 단계
    private final List<TaxExchange> taxExchanges = new ArrayList<>();

    // 혁명을 선언할지 결정 중인 플레이어 (어릿광대 2장 보유). null이면 결정 단계 아님
    // 누가 어릿광대 2장을 가졌는지 드러나지 않도록 공개 상태에는 포함하지 않음
    private String revolutionCandidateId;
    // 이번 판에 선언된 혁명과 선언한 플레이어 (선언은 공개 정보)
    private Revolution revolution;
    private String revolutionDeclarerId;

    // 자리 뽑기 단계 (첫 판 신분 정하기). 뽑은 카드는 공개 정보라 판 시작 후에도 결과 표시용으로 유지
    private boolean seatDrawPhase = false;
    private final List<CardType> seatDrawPile = new ArrayList<>();
    private final Map<String, CardType> seatDraws = new LinkedHashMap<>();

    // 입장. 게임 진행 중이면 다음 판 대기자로 등록
    public synchronized void addPlayer(Player player) {
        if (isNameTaken(player.getName(), player.getId())) {
            throw new GameException(GameException.NAME_TAKEN, "이미 사용 중인 이름입니다. 다른 이름으로 입장해주세요.");
        }

        // 같은 ID 재입장은 중복 추가 없이 허용 (진행 중 재접속, 이름 변경 포함)
        Player existing = findMember(player.getId());
        if (existing != null) {
            existing.setName(player.getName());
            existing.setConnected(true);
            existing.setDisconnectedAt(null);
            return;
        }
        if (players.size() + waitingPlayers.size() >= MAX_PLAYERS) {
            throw new GameException("방이 가득 찼습니다. (최대 " + MAX_PLAYERS + "명)");
        }

        if (isGameStarted) waitingPlayers.add(player);
        else players.add(player);
    }

    // 다른 플레이어(대기자 포함)가 같은 이름을 쓰고 있는지. 공백·대소문자 차이는 같은 이름으로 봄
    public synchronized boolean isNameTaken(String name, String exceptPlayerId) {
        String normalized = name.strip();
        for (Player p : allMembers()) {
            if (!p.getId().equals(exceptPlayerId) && p.getName().strip().equalsIgnoreCase(normalized)) return true;
        }
        return false;
    }

    // 플레이어 + 다음 판 대기자 (메시지 전송 대상)
    public synchronized List<Player> allMembers() {
        List<Player> all = new ArrayList<>(players);
        all.addAll(waitingPlayers);
        return all;
    }

    // 대기 중이면 방에서 제거, 진행 중이면 재접속할 수 있도록 연결 끊김으로만 표시
    public synchronized void disconnectPlayer(String playerId) {
        disconnectPlayer(playerId, Instant.now());
    }

    synchronized void disconnectPlayer(String playerId, Instant now) {
        // 다음 판 대기자는 아직 게임에 참여하지 않았으므로 바로 제거
        if (waitingPlayers.removeIf(p -> p.getId().equals(playerId))) return;

        Player p = findPlayer(playerId);
        if (p == null) return;
        if (isGameStarted) {
            p.setConnected(false);
            p.setDisconnectedAt(now);
        } else {
            players.remove(p);
        }
    }

    // 유예 시간 넘게 연결이 끊긴 플레이어 대신 진행해 게임이 멈추지 않도록 함. 상태가 바뀌었으면 true
    //  - 혁명 결정 대기: 선언하지 않음으로 처리
    //  - 세금 반환 대기: 가장 나쁜(숫자가 큰) 카드를 자동 반환
    //  - 차례: 바닥이 있으면 패스, 선이면 선을 다음 플레이어에게 넘김
    //  - 손패가 남은 플레이어가 모두 끊겼으면 판 종료
    public synchronized boolean actForAwayPlayers(Instant now, Duration grace) {
        if (!isGameStarted) return false;
        boolean changed = false;

        // 자리 뽑기: 아직 안 뽑은 플레이어 대신 뽑기 (모두 뽑으면 바로 카드 배분)
        if (seatDrawPhase) {
            for (Player p : new ArrayList<>(players)) {
                if (seatDrawPhase && !seatDraws.containsKey(p.getId()) && isAway(p, now, grace)) {
                    drawSeatCard(p.getId());
                    changed = true;
                }
            }
            if (seatDrawPhase) return changed;
        }

        if (isRevolutionPending()) {
            if (!isAway(findPlayer(revolutionCandidateId), now, grace)) return false;
            decideRevolution(revolutionCandidateId, false);
            changed = true;
        }

        for (TaxExchange e : taxExchanges) {
            Player receiver = findPlayer(e.getReceiverId());
            if (e.isPending() && isAway(receiver, now, grace)) {
                List<CardType> hand = receiver.getHand();
                List<CardType> worst = new ArrayList<>(hand.subList(hand.size() - e.getPaidCards().size(), hand.size()));
                moveCards(receiver, findPlayer(e.getPayerId()), worst);
                e.complete(worst);
                changed = true;
            }
        }
        if (isTaxPhase()) return changed;

        boolean anyActivePresent = false;
        for (Player p : players) {
            if (!p.getHand().isEmpty() && !isAway(p, now, grace)) anyActivePresent = true;
        }
        if (!anyActivePresent) {
            endGame();
            return true;
        }

        while (isAway(players.get(currentTurnIndex), now, grace)) {
            moveToNextTurn();
            changed = true;
        }
        return changed;
    }

    private boolean isAway(Player p, Instant now, Duration grace) {
        return !p.isConnected() && p.getDisconnectedAt() != null
                && !now.isBefore(p.getDisconnectedAt().plus(grace));
    }

    // 대기자는 항상 접속 중 (끊기면 바로 제거되므로)
    public synchronized boolean hasConnectedPlayers() {
        for (Player p : players) {
            if (p.isConnected()) return true;
        }
        return !waitingPlayers.isEmpty();
    }

    // 게임 시작. 전원 평민(첫 판)이면 자리 뽑기로 신분부터 정하고, 아니면 바로 카드 배분
    public synchronized void startGame(String playerId) {
        validateStart(playerId);
        if (allCitizens()) {
            List<CardType> pile = new ArrayList<>(List.of(CardType.values()));
            pile.remove(CardType.JESTER);
            Collections.shuffle(pile);
            beginSeatDraw(pile);
        } else {
            deal(newShuffledDeck(), true);
        }
    }

    // 테스트에서 정해진 덱으로 (자리 뽑기 없이) 바로 시작할 수 있도록 분리
    synchronized void startGame(String playerId, List<CardType> deck) {
        validateStart(playerId);
        deal(deck, true);
    }

    // 테스트에서 정해진 뽑기 순서로 자리 뽑기를 시작할 수 있도록 분리
    synchronized void startSeatDraw(String playerId, List<CardType> pile) {
        validateStart(playerId);
        beginSeatDraw(pile);
    }

    private void validateStart(String playerId) {
        if (findPlayer(playerId) == null) throw new GameException("방에 참가한 플레이어만 게임을 시작할 수 있습니다.");
        if (isGameStarted) throw new GameException("이미 게임이 진행 중입니다.");
        if (players.size() < MIN_PLAYERS) throw new GameException("최소 " + MIN_PLAYERS + "명이 필요합니다.");
    }

    private boolean allCitizens() {
        for (Player p : players) {
            if (p.getRank() != Rank.CITIZEN) return false;
        }
        return true;
    }

    static List<CardType> newShuffledDeck() {
        List<CardType> deck = new ArrayList<>();
        for (CardType type : CardType.values()) {
            if (type == CardType.JESTER) continue;
            for (int i = 0; i < type.getValue(); i++) {
                deck.add(type);
            }
        }
        deck.add(CardType.JESTER);
        deck.add(CardType.JESTER);
        Collections.shuffle(deck);
        return deck;
    }

    // ---------- 자리 뽑기 (첫 판 신분 정하기) ----------

    // 1~12번 카드를 한 장씩 섞어 두고 각자 한 장씩 뽑음 (숫자가 모두 달라 동점 없음, 최대 10명)
    private void beginSeatDraw(List<CardType> pile) {
        this.isGameStarted = true;
        this.gameOver = false;
        this.seatDrawPhase = true;
        this.seatDrawPile.clear();
        this.seatDrawPile.addAll(pile);
        this.seatDraws.clear();
        this.finishOrder.clear();
        this.taxExchanges.clear();
        this.revolution = null;
        this.revolutionDeclarerId = null;
        this.revolutionCandidateId = null;
        clearTrick();
        for (Player p : players) {
            p.getHand().clear();
        }
    }

    public synchronized void drawSeatCard(String playerId) {
        if (!seatDrawPhase) throw new GameException("자리 뽑기 중이 아닙니다.");
        if (findPlayer(playerId) == null) throw new GameException("이번 판 참가자만 뽑을 수 있습니다.");
        if (seatDraws.containsKey(playerId)) throw new GameException("이미 카드를 뽑았습니다.");

        seatDraws.put(playerId, seatDrawPile.remove(0));
        if (seatDraws.size() == players.size()) finishSeatDraw();
    }

    // 숫자가 낮은(강한) 카드를 뽑은 순서대로 신분을 정하고 자리 배치 후 카드 배분. 첫 판은 세금·혁명 없음
    private void finishSeatDraw() {
        players.sort(Comparator.comparingInt(p -> seatDraws.get(p.getId()).getValue()));
        for (int i = 0; i < players.size(); i++) {
            players.get(i).setRank(rankOf(i, players.size()));
        }
        this.seatDrawPhase = false;
        deal(newShuffledDeck(), false);
    }

    // 카드 배분. applyTax가 false면 (자리 뽑기 직후 첫 판) 세금 교환과 혁명 결정을 건너뜀
    private void deal(List<CardType> deck, boolean applyTax) {
        // 신분 순으로 자리 배치 (달무티가 0번 = 선)
        players.sort(Comparator.comparingInt(p -> p.getRank().getOrder()));

        for (Player p : players) {
            p.getHand().clear();
        }

        int idx = 0;
        for (CardType card : deck) {
            players.get(idx % players.size()).getHand().add(card);
            idx++;
        }

        for (Player p : players) {
            sortHand(p);
        }

        this.isGameStarted = true;
        this.gameOver = false;
        this.finishOrder.clear();
        this.currentTurnIndex = 0;
        this.lastPlayerIndex = -1;
        clearTrick();

        taxExchanges.clear();
        this.revolution = null;
        this.revolutionDeclarerId = null;
        this.revolutionCandidateId = null;
        if (!applyTax) return;  // 자리 뽑기 직후 첫 판: 뽑기 결과는 표시용으로 남겨 둠

        seatDraws.clear();

        // 세금이 걸린 판(이전 판 신분이 있음)에서 어릿광대 2장을 받은 플레이어가 있으면 혁명 여부부터 결정
        Player candidate = findPlayerWithBothJesters();
        if (candidate != null && hasTaxPairs()) {
            this.revolutionCandidateId = candidate.getId();
        } else {
            collectTaxes();
        }
    }

    public synchronized boolean isRevolutionPending() {
        return revolutionCandidateId != null;
    }

    // 어릿광대 2장을 가진 플레이어가 혁명 선언 여부를 결정
    //  - 선언: 세금 교환 없음. 선언자가 농노면 대혁명으로 신분이 뒤집힘
    //  - 안 함: 평소대로 세금 교환
    public synchronized void decideRevolution(String playerId, boolean declare) {
        if (!isRevolutionPending()) throw new GameException("혁명을 결정할 단계가 아닙니다.");
        if (!revolutionCandidateId.equals(playerId)) {
            throw new GameException("혁명은 어릿광대 2장을 가진 플레이어만 선언할 수 있습니다.");
        }
        this.revolutionCandidateId = null;

        if (!declare) {
            collectTaxes();
            return;
        }

        Player declarer = findPlayer(playerId);
        this.revolutionDeclarerId = playerId;
        if (declarer.getRank() == Rank.SERF) {
            this.revolution = Revolution.GREAT_REVOLUTION;
            reverseRanks();
        } else {
            this.revolution = Revolution.REVOLUTION;
        }
    }

    // 대혁명: 달무티↔농노, 총리대신↔소작농. 새 신분 순으로 자리를 바꾸고 새 달무티가 선
    private void reverseRanks() {
        for (Player p : players) {
            switch (p.getRank()) {
                case GREAT_DALMUTI -> p.setRank(Rank.SERF);
                case SERF -> p.setRank(Rank.GREAT_DALMUTI);
                case PRIME_MINISTER -> p.setRank(Rank.TENANT_FARMER);
                case TENANT_FARMER -> p.setRank(Rank.PRIME_MINISTER);
                default -> { }
            }
        }
        players.sort(Comparator.comparingInt(p -> p.getRank().getOrder()));
        this.currentTurnIndex = 0;
    }

    private Player findPlayerWithBothJesters() {
        for (Player p : players) {
            if (Collections.frequency(p.getHand(), CardType.JESTER) == 2) return p;
        }
        return null;
    }

    // 세금을 주고받을 신분 쌍이 하나라도 있는지 (첫 판은 전원 평민이라 없음)
    private boolean hasTaxPairs() {
        return (findPlayerByRank(Rank.SERF) != null && findPlayerByRank(Rank.GREAT_DALMUTI) != null)
                || (findPlayerByRank(Rank.TENANT_FARMER) != null && findPlayerByRank(Rank.PRIME_MINISTER) != null);
    }

    private void collectTaxes() {
        collectTax(Rank.SERF, Rank.GREAT_DALMUTI);
        collectTax(Rank.TENANT_FARMER, Rank.PRIME_MINISTER);
    }

    public synchronized boolean isTaxPhase() {
        for (TaxExchange e : taxExchanges) {
            if (e.isPending()) return true;
        }
        return false;
    }

    // 반환 대기 중인 상위 신분 playerId → 돌려줘야 할 장수
    public synchronized Map<String, Integer> getPendingTaxReturns() {
        Map<String, Integer> pending = new LinkedHashMap<>();
        for (TaxExchange e : taxExchanges) {
            if (e.isPending()) pending.put(e.getReceiverId(), e.getPaidCards().size());
        }
        return pending;
    }

    // 해당 플레이어가 내는 쪽이나 받는 쪽으로 참여한 이번 판 세금 교환
    public synchronized Optional<TaxExchange> findTaxExchange(String playerId) {
        for (TaxExchange e : taxExchanges) {
            if (e.getPayerId().equals(playerId) || e.getReceiverId().equals(playerId)) return Optional.of(e);
        }
        return Optional.empty();
    }

    // 상위 신분이 받은 세금만큼 원하는 카드를 골라 하위 신분에게 돌려줌
    public synchronized void returnTax(String playerId, List<CardType> cards) {
        if (!isTaxPhase()) throw new GameException("세금 교환 단계가 아닙니다.");
        TaxExchange exchange = null;
        for (TaxExchange e : taxExchanges) {
            if (e.getReceiverId().equals(playerId) && e.isPending()) exchange = e;
        }
        if (exchange == null) throw new GameException("돌려줄 카드가 없습니다.");

        int count = exchange.getPaidCards().size();
        if (cards == null || cards.size() != count) throw new GameException(count + "장을 골라 돌려주세요.");

        Player giver = findPlayer(playerId);
        if (!hasCards(giver, cards)) throw new GameException("손패에 없는 카드입니다.");

        moveCards(giver, findPlayer(exchange.getPayerId()), cards);
        exchange.complete(cards);
    }

    // 하위 신분의 가장 좋은(숫자가 낮은) 카드를 세금 장수만큼 상위 신분에게 자동으로 넘김
    private void collectTax(Rank payerRank, Rank receiverRank) {
        Player payer = findPlayerByRank(payerRank);
        Player receiver = findPlayerByRank(receiverRank);
        if (payer == null || receiver == null) return;

        int count = receiverRank.getTaxAmount();
        List<CardType> best = new ArrayList<>(payer.getHand().subList(0, count));
        moveCards(payer, receiver, best);

        taxExchanges.add(new TaxExchange(payer.getId(), receiver.getId(), best));
    }

    private void moveCards(Player from, Player to, List<CardType> cards) {
        for (CardType c : cards) {
            from.getHand().remove(c);
            to.getHand().add(c);
        }
        sortHand(to);
    }

    private void sortHand(Player p) {
        p.getHand().sort(Comparator.comparingInt(CardType::getValue));
    }

    public synchronized void playCards(String playerId, List<CardType> cards) {
        requireTurn(playerId);
        validateMove(cards);

        Player currentPlayer = players.get(currentTurnIndex);
        if (!hasCards(currentPlayer, cards)) throw new GameException("손패에 없는 카드입니다.");

        for (CardType c : cards) {
            currentPlayer.getHand().remove(c);
        }

        this.currentTrickCount = cards.size();
        this.currentTrickType = extractType(cards);
        this.lastPlayerIndex = currentTurnIndex;

        if (currentPlayer.getHand().isEmpty()) {
            finishOrder.add(currentPlayer.getId());
            if (countActivePlayers() <= 1) {
                endGame();
                return;
            }
        }

        moveToNextTurn();
    }

    public synchronized void pass(String playerId) {
        requireTurn(playerId);
        if (currentTrickCount == 0) throw new GameException("선 플레이어는 패스할 수 없습니다.");

        moveToNextTurn();
    }

    private void endGame() {
        // 마지막 남은 플레이어가 꼴찌
        for (Player p : players) {
            if (!p.getHand().isEmpty()) finishOrder.add(p.getId());
        }

        int n = finishOrder.size();
        for (int i = 0; i < n; i++) {
            findPlayer(finishOrder.get(i)).setRank(rankOf(i, n));
        }

        this.isGameStarted = false;
        this.gameOver = true;
        clearTrick();

        // 게임 중 나간 플레이어는 판이 끝나면 정리하고, 다음 판 대기자를 합류시킴
        players.removeIf(p -> !p.isConnected());
        players.addAll(waitingPlayers);
        waitingPlayers.clear();
    }

    private Rank rankOf(int place, int total) {
        if (place == 0) return Rank.GREAT_DALMUTI;
        if (place == total - 1) return Rank.SERF;
        // 4인 이상일 때만 총리대신/소작농 부여
        if (total >= 4 && place == 1) return Rank.PRIME_MINISTER;
        if (total >= 4 && place == total - 2) return Rank.TENANT_FARMER;
        return Rank.CITIZEN;
    }

    private int countActivePlayers() {
        int count = 0;
        for (Player p : players) {
            if (!p.getHand().isEmpty()) count++;
        }
        return count;
    }

    private void clearTrick() {
        this.currentTrickType = null;
        this.currentTrickCount = 0;
    }

    private void requireTurn(String playerId) {
        if (!isGameStarted) throw new GameException("게임이 진행 중이 아닙니다.");
        if (seatDrawPhase) throw new GameException("자리 뽑기가 끝난 뒤에 진행할 수 있습니다.");
        if (isRevolutionPending()) throw new GameException("카드 배분 확인이 끝난 뒤에 진행할 수 있습니다.");
        if (isTaxPhase()) throw new GameException("세금 교환이 끝난 뒤에 진행할 수 있습니다.");
        if (!players.get(currentTurnIndex).getId().equals(playerId)) throw new GameException("내 턴이 아닙니다.");
    }

    private Player findPlayer(String playerId) {
        for (Player p : players) {
            if (p.getId().equals(playerId)) return p;
        }
        return null;
    }

    private Player findMember(String playerId) {
        for (Player p : allMembers()) {
            if (p.getId().equals(playerId)) return p;
        }
        return null;
    }

    private Player findPlayerByRank(Rank rank) {
        for (Player p : players) {
            if (p.getRank() == rank) return p;
        }
        return null;
    }

    private boolean hasCards(Player player, List<CardType> cards) {
        Map<CardType, Integer> owned = new EnumMap<>(CardType.class);
        for (CardType c : player.getHand()) {
            owned.merge(c, 1, Integer::sum);
        }
        for (CardType c : cards) {
            int remaining = owned.getOrDefault(c, 0);
            if (remaining == 0) return false;
            owned.put(c, remaining - 1);
        }
        return true;
    }

    // 손패가 남은 다음 플레이어로 턴을 넘김.
    // 마지막으로 카드를 낸 플레이어 자리를 지나치면 나머지 전원이 패스한 것이므로 바닥을 비움.
    // 그 플레이어가 이미 다 냈다면 다음 순서의 남은 플레이어가 선이 됨.
    private void moveToNextTurn() {
        int idx = currentTurnIndex;
        while (true) {
            idx = (idx + 1) % players.size();
            if (idx == lastPlayerIndex) {
                clearTrick();
                lastPlayerIndex = -1;
            }
            if (!players.get(idx).getHand().isEmpty()) {
                this.currentTurnIndex = idx;
                return;
            }
        }
    }

    private void validateMove(List<CardType> cards) {
        if (cards == null || cards.isEmpty()) throw new GameException("낼 카드를 선택하세요.");
        CardType targetType = extractType(cards);
        if (targetType == null) throw new GameException("같은 종류의 카드만 함께 낼 수 있습니다. (어릿광대 제외)");

        if (currentTrickCount == 0) return;
        if (cards.size() != currentTrickCount) {
            throw new GameException("바닥과 같은 " + currentTrickCount + "장을 내야 합니다.");
        }
        if (targetType.getValue() >= currentTrickType.getValue()) {
            throw new GameException("바닥 카드(" + currentTrickType.getValue() + ")보다 낮은 숫자를 내야 합니다.");
        }
    }

    private CardType extractType(List<CardType> cards) {
        CardType base = null;
        for (CardType c : cards) {
            if (c == null) return null;
            if (c == CardType.JESTER) continue;
            if (base == null) base = c;
            else if (base != c) return null;
        }
        return base == null ? CardType.JESTER : base;
    }
}
