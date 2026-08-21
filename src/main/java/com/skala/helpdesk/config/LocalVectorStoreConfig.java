package com.skala.helpdesk.config;

import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * local 프로파일 전용 인메모리 벡터 스토어.
 *
 * <p><b>왜 필요한가.</b> {@code application-local.yml} 은 pgvector 자동구성을 끈다.
 * Docker 없이 계층만 확인하려는 프로파일이기 때문이다. 그런데 끄기만 하고 대체 빈을 두지 않으면
 * 애플리케이션이 <b>아예 뜨지 않는다</b> — {@code No qualifying bean of type 'VectorStore'} 로
 * 컨텍스트 초기화가 취소된다. 실제로 그 상태로 한동안 방치돼 있었고, README 와 yml 주석은
 * "인메모리로 대체된다"고 적혀 있었다. 문서가 코드보다 강하게 주장하고 있었던 셈이다.
 *
 * <p>{@link SimpleVectorStore} 는 {@code doDelete(Filter.Expression)} 을 지원하므로
 * {@code IngestService} 의 세대 교체(넣고 나서 지우기)가 pgvector 와 같은 방식으로 동작한다.
 * 지원하지 않는 스토어로 바꾸면 인제스트가 이전 세대를 못 지워 청크가 쌓인다.
 *
 * <p>한계는 분명하다 — 프로세스 메모리에만 있고 재시작하면 사라진다. 임베딩 호출은 그대로
 * 발생하므로 API 키는 여전히 필요하다. Docker 를 안 띄운다는 것이지 공짜라는 뜻이 아니다.
 */
@Configuration
@Profile("local")
public class LocalVectorStoreConfig {

    @Bean
    VectorStore vectorStore(EmbeddingModel embeddingModel) {
        return SimpleVectorStore.builder(embeddingModel).build();
    }

    /**
     * 대화 이력도 메모리에 둔다.
     *
     * <p>JDBC 저장소를 H2 에 그대로 붙이면 깨진다. 우리는 대화 ID 폭을 넓히려고 PostgreSQL 용
     * 스키마를 직접 지정해 쓰는데({@code spring.ai.chat.memory.repository.jdbc.schema}),
     * 그 스크립트는 컬럼을 {@code "timestamp"} 로 <b>인용해서</b> 만든다. PostgreSQL 은 인용
     * 식별자를 소문자로 두고 인용 없는 참조도 소문자로 접으므로 둘이 맞는다. H2 는 반대로
     * 인용 없는 참조를 대문자로 접기 때문에 {@code Column "TIMESTAMP" not found} 가 난다.
     *
     * <p>증상이 고약하다 — 기동은 정상이고 문서 검색도 도구 호출도 잘 되는데 <b>멀티턴만</b>
     * 안 된다. "방금 뭘 물어봤죠?" 에 기억이 없다고 답한다. 실제로 이 프로파일에서 그렇게 나왔다.
     *
     * <p>H2 전용 스키마를 하나 더 두는 방법도 있지만, 이 프로파일은 원래 "재시작하면 전부
     * 사라진다" 는 용도다. SQL 방언을 두 벌 관리하는 값어치가 없다.
     */
    @Bean
    ChatMemoryRepository chatMemoryRepository() {
        return new InMemoryChatMemoryRepository();
    }
}
