# HelpDesk AI — 프런트엔드

Astro 5.18 + React 19 아일랜드. 정적 산출(`output: 'static'`)이라 Node 런타임을 추가하지 않습니다 —
백엔드는 Spring Boot 하나로 유지됩니다.

**Node 18.20.8+ / 20.3+ / 22+** 가 필요합니다(Astro 5 요구사항).

## 실행

```bash
# 0) 백엔드 먼저. 프런트는 /api 를 8081 로 넘길 뿐 스스로 데이터를 갖지 않는다.
export OPENAI_API_KEY="sk-..."
./gradlew bootRun          # 저장소 루트에서

# 1) 의존성 (최초 1회)
cd frontend && npm install

# 2) 개발 서버
npm run dev                # → http://localhost:4321
```

문서 페이지(`/docs`)는 마크다운만 읽으므로 **백엔드 없이도 동작**합니다.
나머지 화면은 API 가 필요합니다.

### 인증

백엔드가 `/api/chat/**` 은 인증을, `/api/admin/**` 은 `ROLE_ADMIN` 을 요구합니다.
개발 중에는 Vite 프록시가 환경변수를 읽어 Basic 자격증명을 붙입니다.

```bash
ADMIN_BASIC='<gm계정>:<비밀번호>' USER_BASIC='<player계정>:<비밀번호>' npm run dev
```

| 환경변수 | 붙는 경로 | 없을 때 |
|---|---|---|
| `USER_BASIC` | `/api/chat/**` | 상담 화면에 "로그인이 필요합니다" |
| `ADMIN_BASIC` | `/api/admin/**` | 검색 점검·승인 대기에 "관리자 인증 필요" |
| `API_TARGET` | 프록시 대상 | 기본 `http://localhost:8081` |

계정 값은 백엔드 `SecurityConfig.userDetailsService()` 에 있습니다.
**여기에 옮겨 적지 않습니다** — 같은 비밀번호가 여러 파일에 흩어지면 바꿀 때 빠뜨립니다.

이 주입은 **dev 서버에서만** 동작합니다. 빌드 산출물에는 들어가지 않습니다.

### 빌드

```bash
npm run build     # → frontend/dist/
npm run preview   # 산출물을 그대로 확인 (→ localhost:4321)
```

> **`npm run preview` 는 `/api` 를 프록시하지 않습니다.**
> 프록시는 `vite.server.proxy` 라 **dev 서버 전용**입니다. preview 에서는 페이지는 200 으로 뜨지만
> API 호출이 전부 404 로 떨어집니다 — 화면은 "백엔드에 연결할 수 없습니다" 를 보여줍니다.
> 백엔드와 함께 확인하려면 `npm run dev` 를 쓰세요. preview 는 정적 렌더 결과(문서 페이지·레이아웃)
> 확인용입니다.

## 화면

| 경로 | 내용 | 아일랜드 |
|---|---|---|
| `/` | 상담 — SSE 스트리밍, 근거 문서 표기 | `Chat.jsx` |
| `/docs` | 운영 정책 목록 | 없음 (정적) |
| `/docs/[slug]` | 정책 원문 · 조항 목차 · 앵커 | 없음 (정적) |
| `/admin` | 검색 점검 — 청크·점수·낙차 | `ChunkInspector.jsx` |
| `/admin/tickets` | 승인 대기 — 승인·반려 | `TicketQueue.jsx` |

자바스크립트는 아일랜드가 있는 페이지에만 실립니다. 문서 페이지는 순수 HTML 입니다.

## 백엔드와 맞물리는 지점

**1. 정책 문서를 복사하지 않습니다.**

`src/content.config.ts` 의 content collection 이 `../src/main/resources/docs` 를 직접 가리킵니다.
RAG 가 색인하는 파일과 화면에 렌더되는 파일이 물리적으로 같아야 "출처" 가 거짓말을 하지 않습니다.
복사본을 두면 정책을 고칠 때 한쪽만 고쳐서 인용과 원문이 어긋납니다.
**문서 파일명이나 위치가 바뀌면 빌드가 깨집니다** — 조용히 틀리는 것보다 낫습니다.

**2. `/api/chat/stream` 은 POST 입니다.**

브라우저 `EventSource` 는 GET 전용이라 쓸 수 없습니다.
`fetch` + `ReadableStream` 으로 SSE 를 직접 파싱합니다(`Chat.jsx` 의 `readSse`).

파싱에서 한 가지가 규격과 다릅니다. SSE 규격은 `data:` 뒤 공백 한 칸을 구분자로 보고 제거하지만,
이 백엔드는 구분자 없이 값을 붙여 쓰고 **토큰 자체가 공백으로 시작**합니다. 규격대로 떼면 어절이 붙습니다:

```
제거O → 아이템복구신청은손실이발생한시점으로부터...
제거X → 아이템 복구 신청은 손실이 발생한 시점으로부터...
```

그래서 `data:` 뒤를 **그대로** 씁니다. 나중에 GET 변형이 추가되면 `EventSource` 를 쓰는 순간 이 문제가 재현됩니다.

**3. 사용자 식별자를 보내지 않습니다.**

백엔드가 `Principal`(인증 주체)에서 가져갑니다. 쿼리로 받던 시절에는 남의 계정 ID 를 적어 보내는 것을 막을 방법이 없었습니다.

## 알려진 것

**새 아일랜드 컴포넌트를 추가하면 dev 서버의 React 아일랜드가 전부 죽습니다.**

```
TypeError: Cannot read properties of null (reading 'useState')
```

Vite 가 의존성을 다시 최적화하는 과정에서 React 사본이 갈려 훅 디스패처가 null 이 됩니다.

```bash
rm -rf node_modules/.vite && npm run dev
```

`resolve.dedupe` / `optimizeDeps.include` 를 직접 지정해 막아 보려 했더니 **모든 아일랜드가 깨졌고**,
되돌리자 정상으로 돌아왔습니다. 원인까지 규명하지는 않았습니다 —
`@astrojs/react` 가 이미 넣는 설정과 부딪힌 것으로 짐작할 뿐입니다.
다시 시도해 보실 수는 있지만, 위 캐시 삭제가 확실하고 빠릅니다.

**빌드 산출물에는 이 문제가 없습니다** — dev 서버 한정입니다.

## 아직 안 한 것

- **정적 서빙 연결** — 지금은 `frontend/dist/` 에 두고 프로세스 두 개로 띄웁니다.
  Spring 의 `src/main/resources/static` 으로 내보내면 `./gradlew bootRun` 하나로 뜨지만,
  ① 생성 파일이 백엔드 리소스 트리에 들어가고 ② `SecurityConfig` 의 `.anyRequest().authenticated()`
  때문에 HTML·CSS·JS 까지 401 이 됩니다(Basic 이라 브라우저 로그인 팝업이 뜹니다).
  둘 다 정리된 뒤에 붙이는 게 맞습니다.

- **조항 단위 출처 링크** — `ChunkView` 에는 `heading` 이 있지만 `AnswerDto.Source` 에는 없습니다.
  그래서 상담 화면의 근거 칩은 문서 첫머리로만 갑니다.
  `Source` 에 `heading` 이 실리는 즉시 조항으로 꽂힙니다 — 프런트는 이미 그 값을 쓰도록 되어 있습니다.
