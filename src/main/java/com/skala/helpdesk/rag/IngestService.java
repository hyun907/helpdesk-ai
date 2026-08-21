package com.skala.helpdesk.rag;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

/**
 * 문서 인제스트 — 읽기 → 분할 → 메타데이터 보강 → 저장.
 *
 * 이 단계에서 두 가지를 반드시 지킨다.
 *   1. 재색인: 같은 출처의 이전 세대를 걷어낸다. 안 하면 같은 청크가 쌓인다.
 *      단 <b>넣은 뒤에</b> 지운다 — 순서가 왜 정해져 있는지는 {@link #ingest} 주석에 있다.
 *   2. 메타데이터: 인제스트 시점에만 넣을 수 있다. 빠뜨리면 전량 재색인해야 한다.
 *
 * 둘 다 오류를 내지 않는다. 검색 품질만 조용히 나빠지므로 원인 추적이 가장 어렵다.
 */
@Service
public class IngestService {

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);

    /**
     * 조항 하나가 토큰 상한을 넘을 때만 추가로 자른다.
     * 규정 문서는 크기보다 조항 경계가 우선이므로, 기본 경로는 길이 분할이 아니다.
     */
    private static final int CHUNK_SIZE = 800;
    private static final int MIN_CHUNK_SIZE_CHARS = 200;

    /** 문서 제목(# ...)과 조항 제목(## ...). 조항이 검색 단위가 된다. */
    private static final Pattern TITLE = Pattern.compile("(?m)^#\\s+(.+)$");
    private static final Pattern SECTION = Pattern.compile("(?m)^##\\s+(.+)$");

    /** 이보다 짧은 조항은 앞 조항에 붙인다. 한두 문장짜리 조각은 검색에 걸려도 답이 안 된다. */
    private static final int MIN_SECTION_CHARS = 120;

    /** 세대 구분 메타데이터 키. 교체 조건이 이 키 하나에 걸려 있다. */
    static final String INGEST_ID = "ingestId";

    private final VectorStore vectorStore;

    public IngestService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    /**
     * 문서 하나를 색인한다. <b>넣고 나서 지운다 — 순서가 뒤집히면 안 된다.</b>
     *
     * <p>예전에는 지우고 넣었다. 그 순서에는 되돌릴 수 없는 실패 구간이 있다.
     * 삭제는 벡터 스토어 안에서 끝나지만 저장은 임베딩 API 를 부른다. 그래서
     * 401·429·네트워크 끊김이 나면 <b>지우기만 하고 끝난다.</b> 문서가 색인에서
     * 통째로 사라진 채로 남고, 예외는 인제스트 시점에 한 번 나고 만다.
     *
     * <p>증상은 그 문서에 대한 규정 답변이 전부 "확인되지 않습니다"가 되는 것이다.
     * 검색은 정상 동작하고 오류도 안 나므로, 인제스트 로그를 되짚기 전까지는
     * 프롬프트나 임계값을 의심하며 시간을 쓰게 된다. 실제로 두 번 겪었다.
     *
     * <p>그래서 <b>세대 교체</b>로 바꾼다. 새 청크에 이번 실행의 ingestId 를 붙여 먼저 넣고,
     * 성공한 뒤에 같은 source 의 <i>다른</i> ingestId 를 지운다. 저장이 실패하면 지우는 단계에
     * 닿지 못하므로 이전 세대가 그대로 남는다 — 색인이 비는 일이 없다.
     *
     * <p>대가는 두 가지다.
     * <ul>
     *   <li>저장과 삭제 사이의 짧은 구간에는 두 세대가 함께 검색된다. 같은 문장이 중복으로
     *       걸릴 수 있지만 잠깐이고, 빈 색인보다 낫다.</li>
     *   <li>저장이 중간까지만 성공하면 부분 세대가 남는다. 다음 인제스트가 성공하는 순간
     *       "현재 세대가 아닌 것"을 모두 지우므로 스스로 정리된다.</li>
     * </ul>
     */
    public IngestResult ingest(Resource file, String docType, String dept) {
        String source = file.getFilename();
        String version = LocalDate.now().toString();
        // 실행마다 다른 값이어야 한다. version(날짜)으로는 같은 날 두 번 색인할 때 세대가 겹친다.
        String ingestId = UUID.randomUUID().toString();

        // ① 분할 — 규정 문서는 조항(##)이 검색 단위다
        List<Chunk> chunks = isMarkdown(source) ? splitBySection(file) : splitByLength(file);

        // ② 메타데이터 보강 — 출처 표기·필터·조항 앵커가 전부 여기서 나온다
        List<Document> enriched = chunks.stream().map(chunk -> {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("source", source);        // 출처 표기용
            metadata.put("docType", docType);      // 필터용
            metadata.put("dept", dept);            // 권한·범위 제한용
            metadata.put("version", version);      // 최신본 판별용
            metadata.put("heading", chunk.heading());   // 조항 앵커용 — 지금 안 넣으면 전량 재색인이다
            metadata.put(INGEST_ID, ingestId);     // 세대 구분용 — 교체의 기준점이다
            return new Document(chunk.text(), metadata);
        }).toList();

        // ③ 저장 — 임베딩 호출은 VectorStore 가 알아서 한다. 여기서 터지면 ④ 에 닿지 않는다.
        vectorStore.add(enriched);

        // ④ 이전 세대 정리 — 같은 출처인데 이번 실행이 아닌 것. 저장이 성공한 뒤에만 실행된다.
        vectorStore.delete(previousGenerations(source, ingestId));

        log.info("인제스트 완료 source={} docType={} dept={} chunks={} ingestId={}",
                source, docType, dept, enriched.size(), ingestId);
        return new IngestResult(source, docType, dept, enriched.size());
    }

    /**
     * "같은 출처이면서 이번 실행이 아닌" 청크를 고르는 조건.
     *
     * <p>{@code source} 조건을 빠뜨리면 다른 문서까지 지운다. {@code ingestId} 조건을
     * 빠뜨리면 방금 넣은 것을 도로 지운다. 둘 다 조용히 잘못되는 종류라 한 곳에 모아 둔다.
     *
     * <p><b>전제 — 모든 청크에 ingestId 가 있어야 한다.</b> 이 필터는 결국
     * {@code metadata->>'ingestId' <> '새값'} 이 되는데, 키가 없는 행은 좌변이 NULL 이고
     * NULL 비교의 결과는 TRUE 가 아니라 NULL 이다. SQL 의 3값 논리라 WHERE 절이 그 행을
     * 걸러내지 못한다 — 즉 <b>ingestId 없는 청크는 영원히 안 지워진다.</b>
     *
     * <p>실제로 겪었다. 세대 교체로 바꾸기 <i>전에</i> 색인된 17청크가 ingestId 없이 남아
     * 새로 넣은 17청크와 함께 34청크가 됐고, 검색마다 같은 문장이 두 번 걸렸다. 오류는 없고
     * 재색인을 아무리 돌려도 줄지 않는다.
     *
     * <p>{@code isNull} 절을 OR 로 붙여 해결하려 했으나 쓸 수 없다 — 우변 없는 연산자를
     * 필터 변환기가 거부한다({@code IllegalStateException: expression should have a right
     * operand}). 그래서 조건을 단순하게 두는 대신 <b>전제를 코드로 지킨다</b>:
     * 벡터 스토어에 쓰는 곳은 {@link #ingest} 하나뿐이고, 거기서 모든 청크에 ingestId 를 붙인다.
     * 다른 경로로 문서를 넣는 코드가 생기면 이 전제가 깨지고 위 증상이 되살아난다.
     * 그때는 스토어를 비우고 전량 재색인해야 한다.
     */
    private static Filter.Expression previousGenerations(String source, String ingestId) {
        FilterExpressionBuilder b = new FilterExpressionBuilder();
        return b.and(b.eq("source", source), b.ne(INGEST_ID, ingestId)).build();
    }

    private boolean isMarkdown(String source) {
        return source != null && source.toLowerCase().endsWith(".md");
    }

    /**
     * 조항 단위 분할.
     *
     * <p>길이로 자르면 조항 하나가 통째로 한 청크에 들어가지 않거나, 반대로 여러 조항이
     * 한 청크에 뭉친다. 뭉치면 유사도가 희석돼 정작 그 조항을 묻는 질문이 임계값을 못 넘는다.
     * 실제로 800토큰 길이 분할에서는 문서 하나가 청크 하나가 되어 최고 점수가 0.36까지 떨어졌다.
     *
     * <p>각 청크 앞에 문서 제목을 붙인다. "3회 위반 시 이용 정지"라는 표만 떼어 놓으면
     * 무엇에 대한 규정인지 알 수 없어, 검색에도 답변 생성에도 불리하다.
     */
    private List<Chunk> splitBySection(Resource file) {
        String raw = read(file);
        String title = firstMatch(TITLE, raw);

        List<Chunk> chunks = new ArrayList<>();
        Matcher matcher = SECTION.matcher(raw);

        List<int[]> bounds = new ArrayList<>();
        List<String> headings = new ArrayList<>();
        while (matcher.find()) {
            bounds.add(new int[]{matcher.start(), matcher.end()});
            headings.add(matcher.group(1).trim());
        }

        if (bounds.isEmpty()) {              // 조항 구분이 없는 문서는 길이 분할로 돌아간다
            return splitByLength(file);
        }

        for (int i = 0; i < bounds.size(); i++) {
            int start = bounds.get(i)[0];
            int end = (i + 1 < bounds.size()) ? bounds.get(i + 1)[0] : raw.length();
            String body = raw.substring(start, end).trim();

            // 너무 짧은 조항은 앞 조항에 붙인다
            if (body.length() < MIN_SECTION_CHARS && !chunks.isEmpty()) {
                Chunk previous = chunks.remove(chunks.size() - 1);
                chunks.add(new Chunk(previous.text() + "\n\n" + body, previous.heading()));
                continue;
            }
            chunks.add(new Chunk(withTitle(title, body), headings.get(i)));
        }
        return splitOversized(chunks);
    }

    /** 조항 하나가 토큰 상한을 넘으면 그 안에서만 다시 자른다. 조항 제목은 그대로 물려준다. */
    private List<Chunk> splitOversized(List<Chunk> chunks) {
        var splitter = TokenTextSplitter.builder()
                .withChunkSize(CHUNK_SIZE)
                .withMinChunkSizeChars(MIN_CHUNK_SIZE_CHARS)
                .build();

        List<Chunk> result = new ArrayList<>();
        for (Chunk chunk : chunks) {
            List<Document> pieces = splitter.apply(List.of(new Document(chunk.text())));
            if (pieces.size() <= 1) {
                result.add(chunk);
                continue;
            }
            pieces.forEach(piece -> result.add(new Chunk(piece.getText(), chunk.heading())));
        }
        return result;
    }

    /** 마크다운이 아닌 문서용 경로. 조항 개념이 없으므로 heading 은 비운다. */
    private List<Chunk> splitByLength(Resource file) {
        List<Document> raw = new TikaDocumentReader(file).get();
        return TokenTextSplitter.builder()
                .withChunkSize(CHUNK_SIZE)
                .withMinChunkSizeChars(MIN_CHUNK_SIZE_CHARS)
                .build()
                .apply(raw)
                .stream()
                .map(d -> new Chunk(d.getText(), null))
                .toList();
    }

    private String withTitle(String title, String body) {
        return title == null ? body : "[" + title + "]\n\n" + body;
    }

    private String firstMatch(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private String read(Resource file) {
        try {
            return file.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("문서를 읽지 못했습니다: " + file.getFilename(), e);
        }
    }

    /** 분할 결과 한 조각. 본문과 그 조각이 속한 조항 제목을 함께 들고 다닌다. */
    private record Chunk(String text, String heading) {}

    /**
     * 무엇이 들어갔는지 눈으로 본다.
     * 답변을 만들기 전에 이 창구부터 열어 둔다 — 검색이 잘못됐는데 프롬프트를 고치는 일을 막는다.
     */
    public List<ChunkView> inspect(String query, int topK) {
        return vectorStore.similaritySearch(SearchRequest.builder()
                        .query(query)
                        .topK(topK)
                        .build())
                .stream()
                .map(d -> new ChunkView(
                        (String) d.getMetadata().get("source"),
                        (String) d.getMetadata().get("heading"),
                        (String) d.getMetadata().get("version"),
                        d.getScore(),
                        preview(d.getText())))
                .toList();
    }

    private String preview(String text) {
        if (text == null) {
            return "";
        }
        String flattened = text.replaceAll("\\s+", " ").trim();
        return flattened.length() <= 160 ? flattened : flattened.substring(0, 160) + "…";
    }
}
