import { useCallback, useEffect, useRef, useState } from 'react';

/**
 * 상담 화면.
 *
 * 백엔드의 /api/chat/stream 은 POST 다 — 브라우저 EventSource 는 GET 전용이라 쓸 수 없다.
 * 그래서 fetch 의 ReadableStream 을 직접 읽고 SSE 프레임을 파싱한다.
 */

// Phase 7 에서 백엔드가 Principal 로 바꾸면 이 값은 사라진다.
// 지금은 백엔드가 userId 를 쿼리로 받으므로 인증 계정과 같은 값을 보내야 한다.
const USER_ID = 'player1';

export default function Chat() {
  const [messages, setMessages] = useState([]);
  const [draft, setDraft] = useState('');
  const [busy, setBusy] = useState(false);
  const [sessionId, setSessionId] = useState(() => newSessionId());

  const listRef = useRef(null);
  const buffer = useRef('');      // 아직 화면에 반영되지 않은 토큰
  const frame = useRef(null);     // 예약된 렌더 프레임

  // 새 토큰이 붙을 때마다 아래로 따라간다. 사용자가 위로 올려 읽는 중이면 방해하지 않는다.
  useEffect(() => {
    const el = listRef.current;
    if (!el) return;
    const nearBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 120;
    if (nearBottom) el.scrollTop = el.scrollHeight;
  }, [messages]);

  /**
   * 토큰을 프레임 단위로 모아서 반영한다.
   *
   * 토큰마다 setState 를 부르면 토큰마다 리렌더가 돈다. 답변이 길거나 스트림이 빠르면
   * 눈에 띄게 버벅인다. ref 에 모았다가 프레임당 한 번만 반영한다.
   */
  const pushToken = useCallback((text) => {
    buffer.current += text;
    if (frame.current !== null) return;
    frame.current = requestAnimationFrame(() => {
      frame.current = null;
      const snapshot = buffer.current;
      setMessages((prev) => {
        const next = prev.slice();
        const i = next.length - 1;
        next[i] = { ...next[i], text: snapshot };
        return next;
      });
    });
  }, []);

  const settle = useCallback((patch) => {
    if (frame.current !== null) {
      cancelAnimationFrame(frame.current);
      frame.current = null;
    }
    const snapshot = buffer.current;
    setMessages((prev) => {
      const next = prev.slice();
      const i = next.length - 1;
      next[i] = { ...next[i], text: snapshot, streaming: false, ...patch };
      return next;
    });
  }, []);

  async function send(e) {
    e.preventDefault();
    const question = draft.trim();
    if (!question || busy) return;

    setDraft('');
    setBusy(true);
    buffer.current = '';
    setMessages((prev) => [
      ...prev,
      { role: 'user', text: question },
      { role: 'assistant', text: '', streaming: true, sources: [] },
    ]);

    try {
      const res = await fetch(`/api/chat/stream?userId=${encodeURIComponent(USER_ID)}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
        body: JSON.stringify({ question, sessionId }),
      });

      if (!res.ok || !res.body) {
        settle({ error: describe(res.status) });
        return;
      }

      let sources = [];
      await readSse(res.body, ({ event, data }) => {
        if (event === 'token') pushToken(data);
        else if (event === 'sources') sources = safeParse(data) ?? [];
      });
      settle({ sources });
    } catch {
      settle({ error: BACKEND_DOWN });
    } finally {
      setBusy(false);
    }
  }

  function reset() {
    setMessages([]);
    setSessionId(newSessionId());
    buffer.current = '';
  }

  return (
    <div className="chat">
      <div className="chat-head">
        <span className="session">세션 <code>{sessionId}</code></span>
        <button type="button" className="ghost" onClick={reset} disabled={busy || !messages.length}>
          새 상담
        </button>
      </div>

      <div className="log" ref={listRef}>
        {messages.length === 0 && (
          <div className="empty">
            <p>운영 정책과 계정 데이터를 함께 물어볼 수 있습니다.</p>
            <ul>
              <li>“아이템 복구는 며칠 안에 신청해야 하나요?”</li>
              <li>“제 캐릭터 CH-1001 인벤토리 보여주세요.”</li>
              <li>“제재 이력이 있는지 확인해 주세요.”</li>
            </ul>
          </div>
        )}

        {messages.map((m, i) => (
          <Message key={i} message={m} />
        ))}
      </div>

      <form className="composer" onSubmit={send}>
        <textarea
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
          onKeyDown={(e) => {
            // 한글 조합 중 Enter 는 글자를 확정하는 키다. 제출로 흘려보내지 않는다.
            if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
              e.preventDefault();
              send(e);
            }
          }}
          placeholder="질문을 입력하세요. Shift+Enter 로 줄바꿈."
          rows={2}
          aria-label="질문"
        />
        <button type="submit" disabled={busy || !draft.trim()}>
          {busy ? '답변 중…' : '보내기'}
        </button>
      </form>
    </div>
  );
}

function Message({ message }) {
  const { role, text, streaming, sources, error } = message;

  if (role === 'user') {
    return (
      <div className="msg user">
        <div className="bubble">{text}</div>
      </div>
    );
  }

  return (
    <div className="msg assistant">
      <div className="bubble">
        {renderText(text)}
        {streaming && <span className="caret" aria-hidden="true" />}
        {error && <p className="msg-error">{error}</p>}
      </div>

      {sources?.length > 0 && (
        <div className="sources">
          <span className="sources-label">근거</span>
          {sources.map((s, i) => (
            <a key={i} className="chip" href={docHref(s)}>
              {label(s)}
            </a>
          ))}
        </div>
      )}

      {/* 근거 없이 끝난 답변은 그 사실을 드러낸다.
          출처가 붙은 답변과 붙지 않은 답변이 같아 보이면 확인할 방법이 없다. */}
      {!streaming && !error && sources?.length === 0 && (
        <div className="sources none">근거 문서 없음 — 실시간 조회이거나 검색 결과가 없는 답변입니다.</div>
      )}
    </div>
  );
}

/**
 * 모델은 답변에 마크다운 강조를 섞어 내놓는다("**14일 이내**").
 * 그대로 두면 별표가 화면에 보인다. 강조만 최소로 처리한다 —
 * 마크다운 파서를 통째로 싣지 않는 이유는 번들 대비 얻는 게 이것뿐이기 때문이다.
 *
 * 문자열을 쪼개 React 엘리먼트로 만든다. HTML 을 직접 주입하지 않는다.
 */
function renderText(text) {
  return text.split(/(\*\*[^*\n]+\*\*)/g).map((part, i) =>
    part.length > 4 && part.startsWith('**') && part.endsWith('**')
      ? <strong key={i}>{part.slice(2, -2)}</strong>
      : part,
  );
}

/* ── SSE ──────────────────────────────────────────────────────── */

/**
 * fetch 스트림에서 SSE 프레임을 읽는다.
 * 프레임 경계는 빈 줄(\n\n)이고, 한 프레임은 event: 와 data: 줄로 이뤄진다.
 */
async function readSse(body, onEvent) {
  const reader = body.getReader();
  const decoder = new TextDecoder();
  let buf = '';

  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    buf += decoder.decode(value, { stream: true });

    let cut;
    while ((cut = buf.indexOf('\n\n')) !== -1) {
      const frame = buf.slice(0, cut);
      buf = buf.slice(cut + 2);
      if (frame.trim()) onEvent(parseFrame(frame));
    }
  }

  // 마지막 프레임에 빈 줄이 안 붙어 올 수도 있다. 그대로 두면 sources 를 통째로 잃는다.
  if (buf.trim()) onEvent(parseFrame(buf));
}

function parseFrame(frame) {
  let event = 'message';
  const data = [];
  for (const raw of frame.split('\n')) {
    const line = raw.endsWith('\r') ? raw.slice(0, -1) : raw;
    if (line.startsWith('event:')) {
      event = line.slice(6).trim();
    } else if (line.startsWith('data:')) {
      /**
       * data: 뒤의 값을 그대로 쓴다. 공백 한 칸을 떼지 않는다.
       *
       * SSE 규격은 data: 뒤 공백 한 칸을 구분자로 보고 제거하도록 되어 있지만,
       * 이 백엔드는 구분자 공백 없이 값을 바로 붙여 쓴다. 그리고 토큰 자체가
       * 공백으로 시작한다("·복구"). 규격대로 떼면 어절이 전부 붙어 버린다:
       *   제거O → "아이템복구신청은손실이..."
       *   제거X → "아이템 복구 신청은 손실이..."
       */
      data.push(line.slice(5));
    }
  }
  return { event, data: data.join('\n') };
}

/* ── 보조 ─────────────────────────────────────────────────────── */

const BACKEND_DOWN =
  '백엔드(localhost:8081)에 연결할 수 없습니다. ./gradlew bootRun 으로 먼저 띄워 주세요.';

function describe(status) {
  if (status === 401 || status === 403) {
    return '로그인이 필요합니다 — USER_BASIC 환경변수를 넣고 dev 서버를 다시 띄우세요.';
  }
  if (status === 502 || status === 503 || status === 504) return BACKEND_DOWN;
  if (status === 400) return '요청이 거부되었습니다. 질문이 비어 있거나 2000자를 넘었는지 확인하세요.';
  return `답변을 받지 못했습니다 (HTTP ${status}).`;
}

function label(source) {
  const name = (source.document ?? '').replace(/\.md$/, '');
  return source.heading ? `${name} · ${source.heading}` : name;
}

/**
 * 근거 문서 링크.
 * heading 이 오면 그 조항으로 꽂고, 없으면 문서 첫머리로 보낸다.
 * (백엔드 AnswerDto.Source 에는 아직 heading 이 없다 — 들어오는 즉시 조항 링크가 된다.)
 */
function docHref(source) {
  const doc = `/docs/${(source.document ?? '').replace(/\.md$/, '')}`;
  if (!source.heading) return doc;
  const anchor = source.heading
    .toLowerCase()
    .trim()
    .replace(/[^\p{L}\p{N}\s-]/gu, '')
    .replace(/\s+/g, '-');
  return `${doc}#${anchor}`;
}

function safeParse(text) {
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}

function newSessionId() {
  const rand = globalThis.crypto?.randomUUID?.() ?? String(Math.random()).slice(2);
  return `sess-${rand.slice(0, 8)}`;
}
