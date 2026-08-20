-- 대화 이력 스키마.
--
-- 기본 스크립트를 그대로 쓰지 못하는 이유는 conversation_id 의 폭이다.
-- 기본값은 VARCHAR(36) 으로, 대화 ID 가 UUID 하나라는 전제에서 나온 크기다.
-- 우리 대화 ID 는 "{테넌트}:{계정}:{세션}" 형식이라 그보다 길다.
--
-- 계정 식별자를 빼면 36자에 들어가지만 그러면 안 된다. "이 이용자의 대화를 모두
-- 지워 달라" 는 요청에 응답할 수 없게 된다. 삭제 대상을 고를 수단이 대화 ID 밖에 없다.
-- 그래서 ID 를 줄이는 대신 컬럼을 넓힌다.

CREATE TABLE IF NOT EXISTS SPRING_AI_CHAT_MEMORY (
    conversation_id VARCHAR(200) NOT NULL,
    content TEXT NOT NULL,
    type VARCHAR(10) NOT NULL CHECK (type IN ('USER', 'ASSISTANT', 'SYSTEM', 'TOOL')),
    "timestamp" TIMESTAMP NOT NULL,
    sequence_id BIGINT NOT NULL
    );

CREATE INDEX IF NOT EXISTS SPRING_AI_CHAT_MEMORY_CONVERSATION_ID_TIMESTAMP_IDX
ON SPRING_AI_CHAT_MEMORY(conversation_id, "timestamp");

CREATE INDEX IF NOT EXISTS SPRING_AI_CHAT_MEMORY_CONVERSATION_ID_SEQUENCE_ID_IDX
ON SPRING_AI_CHAT_MEMORY(conversation_id, sequence_id);
