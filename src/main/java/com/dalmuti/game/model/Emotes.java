package com.dalmuti.game.model;

import java.util.List;

// 보낼 수 있는 이모티콘 목록 (화면의 이모티콘 판도 이 목록으로 그림)
public final class Emotes {
    public static final List<String> ALLOWED = List.of(
            "👍", "👏", "😂", "😭", "😡", "😱", "🤔", "🙏", "🔥", "💀", "😎", "🫡", "😘", "🤮");

    private Emotes() {
    }
}
