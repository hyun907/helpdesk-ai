package com.skala.helpdesk.chat;

import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.memory.ChatMemory;
import reactor.core.publisher.Flux;

/**
 * 한 번의 모델 호출에 필요한 재료 전부.
 *
 * <p><b>왜 이 record 가 필요한가.</b> 폴백은 "같은 요청을 다른 모델로 한 번 더 부르는 일"이다.
 * 그러려면 재료가 한 덩어리로 남아 있어야 한다. 재료를 호출부에 흩어 두고 폴백 쪽에서 다시 조립하면
 * 한쪽만 고쳐지는 날이 온다 — 실제로 위험한 것은 <b>도구와 도구 컨텍스트가 빠진 채</b> 폴백이 도는 경우다.
 * 그러면 모델은 캐릭터 정보를 조회할 수단 없이 캐릭터에 대해 답하게 되고, 그건 지어내기가 된다.
 *
 * <p>{@code toolContext} 에 담긴 값은 복사하지 않는다(맵만 복사한다).
 * 안에 든 toolTrace 집합은 도구가 실행 사실을 적어 넣는 <b>공유 자리</b>라서,
 * 여기서 깊은 복사를 하면 주 모델 경로에서 쓴 흔적이 호출부에 보이지 않게 된다.
 */
public record ChatCall(String userText,
                       String conversationId,
                       Map<String, Object> toolContext,
                       List<Object> tools) {

    public ChatCall {
        toolContext = Map.copyOf(toolContext);
        tools = List.copyOf(tools);
    }

    /** 동기 호출. 응답 문자열이 아니라 {@code ChatClientResponse} 를 돌려준다 — 근거는 context() 에만 있다. */
    ChatClientResponse callOn(ChatClient client) {
        return spec(client).call().chatClientResponse();
    }

    /** 스트리밍 호출. */
    Flux<ChatClientResponse> streamOn(ChatClient client) {
        return spec(client).stream().chatClientResponse();
    }

    /**
     * 주 모델이든 보조 모델이든 요청을 만드는 방식은 같아야 한다.
     * 이 메서드가 유일한 조립 지점이다.
     */
    private ChatClient.ChatClientRequestSpec spec(ChatClient client) {
        return client.prompt()
                .user(userText)
                .tools(tools.toArray())
                .toolContext(toolContext)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId));
    }
}
