// dto/ActionRequest.java
package com.dalmuti.game.dto;

import com.dalmuti.game.model.CardType;
import lombok.Getter;
import lombok.Setter;
import java.util.List;

// 플레이어 식별/이름은 요청 본문이 아니라 로그인 세션(Principal)에서 가져옴
@Getter
@Setter
public class ActionRequest {
    private List<CardType> cards;
}
