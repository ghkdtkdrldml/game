// 게임 화면 스크립트 (모바일 우선). GAME_CONFIG(로그인 정보, 표시 이름)는 game.html에서 서버가 주입
// 서버에서 받은 최신 상태(room, me)를 기준으로 render()가 화면 전체를 다시 그림

const RECONNECT_DELAY_MS = 3000;
const TOAST_MS = 2500;
const MIN_PLAYERS = 2;

const myPlayerId = GAME_CONFIG.playerId;
const cardInfo = GAME_CONFIG.cards;       // { DALMUTI: { value: 1, name: '달무티' }, ... }
const rankNames = GAME_CONFIG.rankNames;
// 카드 종류를 숫자 오름차순으로 (좋은 카드가 앞)
const cardOrder = Object.keys(cardInfo).sort((a, b) => cardInfo[a].value - cardInfo[b].value);

let stompClient = null;
let connected = false;
let reconnectTimer = null;
let room = null;       // 방 공개 상태 (/topic/room)
let me = null;         // 내 손패, 세금 내역 (/user/queue/private)
let selected = {};     // 선택한 카드 종류 → 장수

const $ = id => document.getElementById(id);

// ---------------- 연결 ----------------

// 페이지를 열면 자동으로 방에 입장. 연결이 끊기면 잠시 후 다시 연결해 재입장
function connect() {
    clearTimeout(reconnectTimer);
    reconnectTimer = null;

    // 로그인 세션 쿠키로 인증되므로 별도 헤더 없이 연결
    stompClient = Stomp.over(new SockJS('/ws-dalmuti'));
    stompClient.debug = null;

    stompClient.connect({}, function () {
        connected = true;
        setConnection('ok', '연결됨');
        log('서버에 연결되었습니다.');

        stompClient.subscribe('/topic/room', message => onRoomState(JSON.parse(message.body)));
        stompClient.subscribe('/user/queue/private', message => {
            const prevMe = me;
            me = JSON.parse(message.body);
            selected = {};
            if (!prevMe?.canDecideRevolution && me.canDecideRevolution) {
                toast('어릿광대 2장! 혁명을 선언할 수 있습니다', 'turn');
                if (navigator.vibrate) navigator.vibrate([100, 50, 100]);
            }
            render();
        });
        stompClient.subscribe('/user/queue/errors', message => {
            const error = JSON.parse(message.body);
            log('⚠ ' + error.message);
            toast(error.message, 'error');
            // 입장하려는데 같은 이름이 이미 방에 있으면 이름을 바꾸도록 로그인 화면으로
            if (error.code === 'NAME_TAKEN') {
                setTimeout(() => location.href = '/login?error=nameTaken', 1500);
            }
        });

        // 방 입장 요청 (이름은 로그인 정보로 서버가 처리). 게임 중 재접속이면 손패 그대로 복귀
        stompClient.send('/app/game/join', {});
    }, function (error) {
        connected = false;
        // 로그인 세션이 만료된 경우 다시 로그인
        if (String(error).includes('로그인이 필요합니다')) {
            toast('로그인이 만료되었습니다. 다시 입장해주세요.', 'error');
            setTimeout(() => location.href = '/login', 1500);
            return;
        }
        setConnection('bad', '재연결 중');
        render();
        if (!reconnectTimer) {
            log('서버 연결이 끊겼습니다. 잠시 후 다시 연결합니다.');
            reconnectTimer = setTimeout(connect, RECONNECT_DELAY_MS);
        }
    });
}

// 모바일에서 앱 전환 후 돌아왔을 때 끊겨 있으면 기다리지 않고 바로 재연결
document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible' && !connected) connect();
});

window.addEventListener('load', connect);

function setConnection(state, text) {
    $('conn').className = 'conn ' + state;
    $('connText').textContent = text;
}

function send(destination, body) {
    if (!connected) {
        toast('서버에 연결되어 있지 않습니다.', 'error');
        return;
    }
    stompClient.send(destination, {}, body ? JSON.stringify(body) : '');
}

// ---------------- 상태 수신 ----------------

function onRoomState(next) {
    const prev = room;
    room = next;

    // 상태 변화 알림
    if (!prev?.seatDrawPhase && next.seatDrawPhase && !amWaiting(next)) {
        log('🎴 자리 뽑기를 시작합니다. 숫자가 낮은 카드를 뽑을수록 높은 신분이 됩니다.');
        toast('자리 뽑기! 카드를 뽑아 신분을 정합니다', 'turn');
        if (navigator.vibrate) navigator.vibrate(150);
    }
    if (prev?.seatDrawPhase && !next.seatDrawPhase && next.gameStarted) {
        const result = [...next.players]
            .filter(p => next.seatDraws[p.id])
            .sort((a, b) => cardInfo[next.seatDraws[a.id]].value - cardInfo[next.seatDraws[b.id]].value)
            .map(p => `${cardInfo[next.seatDraws[p.id]].value} ${p.name}(${rankNames[p.rank]})`)
            .join(', ');
        log('🎴 자리 뽑기 결과: ' + result);
        const myRank = next.players.find(p => p.id === myPlayerId)?.rank;
        if (myRank) toast(`자리 결정! 나는 ${rankNames[myRank]} — 첫 판은 세금 없이 시작합니다`, 'turn');
    }
    if (!prev?.revolution && next.revolution) {
        const msg = revolutionMessage(next);
        log(msg);
        toast(msg, 'turn');
    }
    if (!prev?.taxPhase && next.taxPhase) log('💰 세금 교환 단계가 시작되었습니다.');
    if (prev?.taxPhase && !next.taxPhase && next.gameStarted) {
        log('💰 세금 교환 완료! 달무티부터 시작합니다.');
        toast('세금 교환 완료! 게임을 시작합니다.');
    }
    if (!prev?.gameOver && next.gameOver) {
        const place = next.finishOrder.indexOf(myPlayerId);
        const myRank = next.players.find(p => p.id === myPlayerId)?.rank;
        const result = place >= 0 ? `나는 ${place + 1}등 (${rankNames[myRank] || ''})` : '';
        log('🏁 게임 종료! ' + result);
        toast('게임 종료! ' + result);
    }
    if (amWaiting(prev) && !amWaiting(next) && next.players.some(p => p.id === myPlayerId)) {
        toast('대기 끝! 다음 판부터 함께합니다', 'turn');
    }
    if (!isMyTurn(prev) && isMyTurn(next)) {
        toast('내 차례입니다!', 'turn');
        if (navigator.vibrate) navigator.vibrate(150);
    }

    render();
}

// 카드를 내는 단계 (자리 뽑기·혁명 결정·세금 교환이 끝난 뒤)
function isPlaying(r) {
    return !!(r && r.gameStarted && !r.seatDrawPhase && !r.revolutionPending && !r.taxPhase);
}

function haveDrawnSeat(r) {
    return !!r?.seatDraws?.[myPlayerId];
}

// 게임 도중 들어와 다음 판을 기다리는 중(관전)인지
function amWaiting(r) {
    return !!(r && r.waitingPlayers.some(p => p.id === myPlayerId));
}

// 연결 끊긴 플레이어를 기다려주는 시간 (예: "3분", "90초")
function graceText() {
    const s = GAME_CONFIG.disconnectGraceSeconds;
    return s % 60 === 0 ? `${s / 60}분` : `${s}초`;
}

function isMyTurn(r) {
    return isPlaying(r) && r.players[r.currentTurnIndex]?.id === myPlayerId;
}

function revolutionMessage(r) {
    const name = playerName(r.revolutionDeclarerId);
    return r.revolution === 'GREAT_REVOLUTION'
        ? `🔥 ${name} 님의 대혁명! 신분이 뒤집히고 세금이 없습니다`
        : `🔥 ${name} 님의 혁명! 이번 판은 세금이 없습니다`;
}

function myPendingTaxCount() {
    return room?.pendingTaxReturns?.[myPlayerId] || 0;
}

function selectedCards() {
    const cards = [];
    for (const type of cardOrder) {
        for (let i = 0; i < (selected[type] || 0); i++) cards.push(type);
    }
    return cards;
}

function playerName(id) {
    return room?.players.find(p => p.id === id)?.name
        || room?.waitingPlayers.find(p => p.id === id)?.name
        || id;
}

// ---------------- 화면 그리기 ----------------

function render() {
    if (!room) return;
    renderStatus();
    renderPlayers();
    renderHand();
    $('waitBanner').classList.toggle('active', amWaiting(room));
    renderRevolutionBanner();
    renderTaxBanner();
    renderActions();
}

function renderRevolutionBanner() {
    const active = !!me?.canDecideRevolution;
    $('revBanner').classList.toggle('active', active);
    if (!active) return;

    const myRank = room.players.find(p => p.id === myPlayerId)?.rank;
    $('revText').textContent = myRank === 'SERF'
        ? '농노인 내가 선언하면 대혁명! 신분이 뒤집혀 내가 달무티가 되고, 세금 교환도 없습니다.'
        : '선언하면 이번 판은 세금 교환 없이 바로 시작합니다. 선언하지 않으면 평소대로 세금을 교환합니다.';
}

function cardFace(type, extraClass = '') {
    const el = document.createElement('div');
    el.className = `card-face ${type === 'JESTER' ? 'jester' : ''} ${extraClass}`;
    const num = document.createElement('span');
    num.className = 'num';
    num.textContent = cardInfo[type].value;
    const name = document.createElement('span');
    name.className = 'name';
    name.textContent = cardInfo[type].name;
    el.append(num, name);
    return el;
}

function renderStatus() {
    const trick = $('trick');
    trick.replaceChildren();

    const text = document.createElement('div');
    text.className = 'trick-text';

    if (room.gameOver) {
        text.textContent = '🏁 게임 종료! 다음 판을 시작할 수 있습니다';
        trick.append(text);
    } else if (!room.gameStarted) {
        text.textContent = `대기 중 — ${room.players.length}명 참여 (최소 ${MIN_PLAYERS}명)`;
        trick.append(text);
    } else if (room.seatDrawPhase) {
        const drawn = Object.keys(room.seatDraws).length;
        text.textContent = `🎴 자리 뽑기 — 숫자가 낮은 카드일수록 높은 신분 (${drawn}/${room.players.length}명 뽑음)`;
        trick.append(text);
        if (haveDrawnSeat(room)) {
            const row = document.createElement('div');
            row.className = 'trick-cards';
            const label = document.createElement('span');
            label.className = 'trick-text';
            label.textContent = '내가 뽑은 카드';
            row.append(cardFace(room.seatDraws[myPlayerId]), label);
            trick.append(row);
        }
    } else if (room.revolutionPending) {
        // 누가 결정 중인지는 표시하지 않음 (어릿광대 2장 보유가 드러나지 않도록)
        text.textContent = '🃏 카드 배분 확인 중…';
        trick.append(text);
    } else if (room.taxPhase) {
        text.textContent = '💰 세금 교환 중 — 끝나면 게임이 시작됩니다';
        trick.append(text);
    } else if (room.currentTrickType) {
        const row = document.createElement('div');
        row.className = 'trick-cards';
        const count = document.createElement('span');
        count.className = 'trick-count';
        count.textContent = `× ${room.currentTrickCount}`;
        row.append(cardFace(room.currentTrickType), count);
        trick.append(row);
    } else {
        text.textContent = '바닥이 비어 있습니다 — 선 플레이어가 아무 카드나 냅니다';
        trick.append(text);
    }

    // 자리 뽑기 직후 첫 판이면 표시 (뽑기 결과는 다음 판 시작 때 지워짐)
    if (isPlaying(room) && Object.keys(room.seatDraws).length > 0) {
        const tag = document.createElement('div');
        tag.className = 'rev-tag';
        tag.textContent = '🎴 첫 판 — 세금 없음';
        trick.append(tag);
    }

    // 혁명이 선언된 판이면 표시
    if (room.gameStarted && room.revolution) {
        const tag = document.createElement('div');
        tag.className = 'rev-tag';
        tag.textContent = room.revolution === 'GREAT_REVOLUTION' ? '🔥 대혁명 판 — 신분 역전, 세금 없음' : '🔥 혁명 판 — 세금 없음';
        trick.append(tag);
    }

    const turn = $('turnText');
    const showTurn = isPlaying(room);
    turn.classList.toggle('hidden', !showTurn);
    if (showTurn) {
        const mine = isMyTurn(room);
        turn.classList.toggle('mine', mine);
        turn.textContent = mine ? '🔔 내 차례!' : `${playerName(room.players[room.currentTurnIndex].id)} 님 차례`;
    }
}

function renderPlayers() {
    $('playersTitle').textContent = `플레이어 ${room.players.length}명`;
    const list = $('players');
    list.replaceChildren();

    room.players.forEach((p, idx) => {
        const chip = document.createElement('div');
        chip.className = 'player-chip';
        if (p.id === myPlayerId) chip.classList.add('me');
        if (isPlaying(room) && idx === room.currentTurnIndex) chip.classList.add('turn');
        if (!p.connected) chip.classList.add('away');

        // 이름은 사용자 입력이므로 textContent 사용 (XSS 방지)
        const name = document.createElement('div');
        name.className = 'p-name';
        name.textContent = p.name + (p.id === myPlayerId ? ' (나)' : '');

        const meta = document.createElement('div');
        meta.className = 'p-meta';
        meta.textContent = `${rankNames[p.rank] || '평민'} · ${p.handCount}장`;

        chip.append(name, meta);

        if (room.seatDrawPhase) {
            const drawn = room.seatDraws[p.id];
            chip.append(drawn
                ? badge(`🎴 ${cardInfo[drawn].value} ${cardInfo[drawn].name}`, 'var(--gold-light)')
                : badge('뽑는 중…', 'var(--muted)'));
        }
        const place = room.finishOrder.indexOf(p.id);
        if (place >= 0) chip.append(badge(`🏅 ${place + 1}등`, 'var(--green)'));
        const taxToReturn = room.pendingTaxReturns[p.id];
        if (taxToReturn) chip.append(badge(`💰 ${taxToReturn}장 반환 대기`, 'var(--gold)'));
        if (!p.connected) chip.append(badge(`연결 끊김 · ${graceText()} 후 자동 진행`, 'var(--muted)'));

        list.append(chip);
    });

    // 다음 판 대기자 (이름은 사용자 입력이므로 textContent)
    const waitingList = $('waitingList');
    const waiting = room.waitingPlayers;
    waitingList.classList.toggle('hidden', waiting.length === 0);
    waitingList.textContent = `👀 다음 판 대기: ${waiting.map(p => p.name + (p.id === myPlayerId ? ' (나)' : '')).join(', ')}`;

    // 현재 차례인 플레이어가 보이도록 목록만 가로 스크롤 (scrollIntoView는 페이지 세로 스크롤까지 움직이므로 사용 안 함)
    const turnChip = list.querySelector('.turn');
    if (turnChip) {
        list.scrollTo({ left: turnChip.offsetLeft - (list.clientWidth - turnChip.offsetWidth) / 2, behavior: 'smooth' });
    }
}

function badge(text, color) {
    const el = document.createElement('div');
    el.className = 'p-badge';
    el.style.color = color;
    el.textContent = text;
    return el;
}

// 손패는 같은 카드끼리 묶어 표시. 탭할 때마다 선택 장수 +1, 최대에서 한 번 더 누르면 0
function renderHand() {
    const hand = me?.hand || [];
    const total = hand.length;
    const selectedCount = selectedCards().length;
    $('handTitle').textContent = selectedCount ? `내 손패 ${total}장 · ${selectedCount}장 선택` : `내 손패 ${total}장`;
    $('btnClear').classList.toggle('hidden', selectedCount === 0);

    const container = $('hand');
    container.replaceChildren();

    if (total === 0) {
        const empty = document.createElement('div');
        empty.className = 'hand-empty';
        const place = room.finishOrder.indexOf(myPlayerId);
        if (amWaiting(room)) empty.textContent = '다음 판부터 카드를 받습니다';
        else if (place >= 0 && room.gameStarted) empty.textContent = `🎉 모두 냈습니다! ${place + 1}등`;
        else empty.textContent = '게임이 시작되면 카드가 나옵니다';
        container.append(empty);
        return;
    }

    const counts = {};
    hand.forEach(c => counts[c] = (counts[c] || 0) + 1);

    // 세금으로 받은 카드 (반환 대기 중일 때만 표시)
    const tax = me?.tax;
    const received = {};
    if (tax && tax.role === 'RECEIVER' && !tax.returnedCards) {
        tax.paidCards.forEach(c => received[c] = (received[c] || 0) + 1);
    }

    for (const type of cardOrder) {
        const count = counts[type];
        if (!count) continue;
        const sel = selected[type] || 0;

        const tile = cardFace(type, 'card-tile');
        tile.setAttribute('role', 'button');
        if (sel > 0) tile.classList.add('selected');
        if (received[type]) tile.classList.add('received');

        const countEl = document.createElement('span');
        countEl.className = 'count';
        countEl.textContent = `×${count}`;
        tile.append(countEl);

        if (sel > 0) {
            const selEl = document.createElement('span');
            selEl.className = 'sel';
            selEl.textContent = sel;
            tile.append(selEl);
        }
        if (received[type]) {
            const recvEl = document.createElement('span');
            recvEl.className = 'recv';
            recvEl.textContent = `★${received[type]}`;
            tile.append(recvEl);
        }

        tile.onclick = () => {
            selected[type] = (sel + 1) % (count + 1);
            render();
        };
        container.append(tile);
    }
}

function clearSelection() {
    selected = {};
    render();
}

// 세금 교환 단계 배너: 내 역할에 맞는 안내 + 반환 대기 중인 플레이어
function renderTaxBanner() {
    const active = !!room.taxPhase;
    $('taxBanner').classList.toggle('active', active);
    $('handPanel').classList.toggle('tax-mode', active && myPendingTaxCount() > 0);
    if (!active) return;

    const tax = me?.tax;
    let task;
    if (!tax) {
        task = '이번 교환에는 참여하지 않습니다. 잠시 기다려 주세요.';
    } else {
        const who = `${rankNames[tax.counterpartRank] || ''} ${tax.counterpartName}`;
        if (tax.role === 'RECEIVER' && !tax.returnedCards) {
            task = `${who}에게서 ${cardList(tax.paidCards)}을(를) 받았습니다. 돌려줄 카드 ${tax.paidCards.length}장을 골라주세요. (★ = 받은 카드)`;
        } else if (tax.role === 'RECEIVER') {
            task = `${who}에게 ${cardList(tax.returnedCards)}을(를) 돌려줬습니다. 다른 플레이어를 기다리는 중입니다.`;
        } else if (!tax.returnedCards) {
            task = `가장 좋은 카드 ${cardList(tax.paidCards)}을(를) ${who}에게 세금으로 냈습니다. 돌려받을 카드를 기다리는 중입니다.`;
        } else {
            task = `${who}에게서 ${cardList(tax.returnedCards)}을(를) 돌려받았습니다.`;
        }
    }
    // 이름은 사용자 입력이므로 textContent 사용
    $('taxMyTask').textContent = task;

    const waiting = Object.entries(room.pendingTaxReturns)
        .map(([id, n]) => `${playerName(id)} (${n}장)`)
        .join(', ');
    $('taxWaiting').textContent = `반환 대기: ${waiting}`;
}

function cardList(cards) {
    return cards.map(c => `[${cardInfo[c].value} ${cardInfo[c].name}]`).join(' ');
}

// 하단 고정 버튼: 상황에 맞는 버튼만 표시
function renderActions() {
    const show = (id, visible) => $(id).classList.toggle('hidden', !visible);
    const count = selectedCards().length;
    let hint = '';

    // 다음 판 대기(관전) 중이면 비활성 버튼과 안내만 표시
    if (amWaiting(room)) {
        ['btnStart', 'btnDraw', 'btnRevolution', 'btnNoRevolution', 'btnTax', 'btnPass'].forEach(id => show(id, false));
        show('btnPlay', true);
        $('btnPlay').textContent = '다음 판 대기 중';
        $('btnPlay').disabled = true;
        $('actionHint').textContent = '관전 중입니다 — 이번 판이 끝나면 참여합니다';
        return;
    }

    const lobby = !room.gameStarted;
    const inSeatDraw = room.gameStarted && room.seatDrawPhase;
    const canDraw = inSeatDraw && !haveDrawnSeat(room);
    const inRevolution = room.gameStarted && room.revolutionPending;
    const canDecide = inRevolution && !!me?.canDecideRevolution;
    const taxToReturn = myPendingTaxCount();
    const inTax = room.gameStarted && !room.revolutionPending && room.taxPhase;
    const playing = isPlaying(room);
    // 다른 사람의 결정/반환을 기다리는 중이면 비활성 '내기' 버튼만 표시
    const waiting = (inSeatDraw && !canDraw) || (inRevolution && !canDecide) || (inTax && taxToReturn === 0);

    show('btnStart', lobby);
    show('btnDraw', canDraw);
    show('btnRevolution', canDecide);
    show('btnNoRevolution', canDecide);
    show('btnTax', inTax && taxToReturn > 0);
    show('btnPass', playing);
    show('btnPlay', playing || waiting);

    if (lobby) {
        $('btnStart').textContent = room.gameOver ? '다음 판 시작' : '게임 시작';
        $('btnStart').disabled = !connected || room.players.length < MIN_PLAYERS;
        hint = room.players.length < MIN_PLAYERS ? `최소 ${MIN_PLAYERS}명이 모여야 시작할 수 있습니다` : '';
    } else if (canDraw) {
        $('btnDraw').disabled = !connected;
        hint = '카드를 뽑아 이번 판 신분을 정하세요';
    } else if (inSeatDraw) {
        $('btnPlay').textContent = '내기';
        $('btnPlay').disabled = true;
        hint = '다른 사람들이 다 뽑을 때까지 기다리는 중';
    } else if (canDecide) {
        $('btnRevolution').disabled = !connected;
        $('btnNoRevolution').disabled = !connected;
        hint = '혁명을 선언할지 선택하세요';
    } else if (inRevolution) {
        $('btnPlay').textContent = '내기';
        $('btnPlay').disabled = true;
        hint = '카드 배분 확인 중…';
    } else if (inTax && taxToReturn > 0) {
        $('btnTax').textContent = `돌려주기 (${count}/${taxToReturn})`;
        $('btnTax').disabled = !connected || count !== taxToReturn;
        hint = `돌려줄 카드 ${taxToReturn}장을 골라주세요`;
    } else if (inTax) {
        $('btnPlay').textContent = '내기';
        $('btnPlay').disabled = true;
        hint = '세금 교환이 끝나길 기다리는 중';
    } else {
        const myTurn = isMyTurn(room) && connected;
        $('btnPlay').textContent = count ? `내기 (${count}장)` : '내기';
        $('btnPlay').disabled = !myTurn || count === 0;
        $('btnPass').disabled = !myTurn || room.currentTrickCount === 0;
        if (!myTurn) hint = '내 차례가 아닙니다';
        else if (room.currentTrickCount === 0) hint = '선입니다 — 원하는 카드를 같은 종류로 내세요';
        else hint = `${room.currentTrickCount}장, ${cardInfo[room.currentTrickType].value}보다 낮은 숫자를 내세요`;
    }
    $('actionHint').textContent = hint;
}

// ---------------- 액션 ----------------

function startGame() {
    send('/app/game/start');
}

function drawSeatCard() {
    send('/app/game/draw');
}

function decideRevolution(declare) {
    send('/app/game/revolution', { declare });
    log(declare ? '혁명 선언' : '혁명 선언하지 않음');
}

function playSelectedCards() {
    const cards = selectedCards();
    if (cards.length === 0) {
        toast('낼 카드를 선택하세요.', 'error');
        return;
    }
    send('/app/game/play', { cards });
    log(`카드 제출: ${cardList(cards)}`);
}

function returnTax() {
    const cards = selectedCards();
    const count = myPendingTaxCount();
    if (cards.length !== count) {
        toast(`돌려줄 카드 ${count}장을 선택하세요.`, 'error');
        return;
    }
    send('/app/game/tax', { cards });
    log(`세금 반환: ${cardList(cards)}`);
}

function passTurn() {
    send('/app/game/pass');
    log('패스');
}

// ---------------- 알림 ----------------

function toast(message, type = '') {
    const el = document.createElement('div');
    el.className = `toast ${type}`;
    el.textContent = message;
    $('toasts').append(el);
    setTimeout(() => {
        el.classList.add('hide');
        setTimeout(() => el.remove(), 300);
    }, TOAST_MS);
}

function log(msg) {
    const logBox = $('logBox');
    const entry = document.createElement('div');
    entry.className = 'log-entry';
    entry.textContent = `[${new Date().toLocaleTimeString()}] ${msg}`;
    logBox.append(entry);
    logBox.scrollTop = logBox.scrollHeight;
}
