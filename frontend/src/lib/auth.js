/**
 * 인증 상태의 단일 지점.
 *
 * 백엔드는 세션을 만들지 않는다(SecurityConfig 의 STATELESS + HTTP Basic).
 * 그래서 "로그인 상태"라는 것이 서버에 없다 — 요청마다 자격증명을 다시 실어야 한다.
 * 브라우저가 그 값을 들고 있어야 하고, 그 보관을 여기 한 곳으로 모은다.
 *
 * 보관 위치는 sessionStorage 다. localStorage 가 아니다:
 * 탭을 닫으면 사라지므로 노출 창이 브라우저를 켜 둔 동안이 아니라 탭을 연 동안으로 줄어든다.
 *
 * 그래도 한계는 분명하다 — XSS 가 한 번이라도 성립하면 자격증명은 그대로 읽힌다.
 * Basic 인증을 클라이언트에 보관하는 방식 자체의 성질이고, 실습 범위에서 받아들인 트레이드오프다.
 * 운영이라면 서버가 발급한 단기 토큰을 HttpOnly 쿠키로 내리고 이 파일은 사라져야 한다.
 */

/** 보관 키. BaseLayout 의 첫 페인트 전 스크립트도 이 값을 읽는다 —
 *  거기서 문자열을 다시 적지 않도록 내보낸다(두 곳이 어긋나면 메뉴가 안 뜬다). */
export const CRED_KEY = 'helpdesk.cred';

/** 상태가 바뀌면 화면 여러 곳이 같이 움직여야 한다(헤더 배지·게이트·본문). */
const listeners = new Set();

export function subscribe(fn) {
  listeners.add(fn);
  return () => listeners.delete(fn);
}

function emit() {
  for (const fn of listeners) fn(read());
}

/** 저장된 자격증명. 없으면 null. */
export function read() {
  if (typeof sessionStorage === 'undefined') return null;
  try {
    const raw = sessionStorage.getItem(CRED_KEY);
    return raw ? JSON.parse(raw) : null;
  } catch {
    // 저장소를 막아 둔 브라우저(시크릿 모드 설정에 따라)에서는 접근 자체가 던진다.
    // 그 경우 "로그인 안 된 상태"로 취급한다 — 화면은 로그인 폼을 띄우고 계속 동작한다.
    return null;
  }
}

function write(value) {
  try {
    if (value) sessionStorage.setItem(CRED_KEY, JSON.stringify(value));
    else sessionStorage.removeItem(CRED_KEY);
  } catch {
    /* 저장 못 해도 이번 세션 동안 메모리로는 못 버틴다 — 조용히 넘어간다 */
  }
  emit();
}

export function clear() {
  write(null);
}

/**
 * 로그인 시도.
 *
 * 서버에 "로그인" 엔드포인트가 없으므로, 실제로 인증이 필요한 경로를 한 번 호출해서
 * 자격증명이 통하는지 확인한다. 성공해야만 보관한다 —
 * 확인 없이 저장하면 오타를 넣고도 로그인된 화면을 보게 되고, 실패는 다음 요청까지 미뤄진다.
 *
 * 역할도 같은 방식으로 판별한다. 서버가 역할을 알려주는 엔드포인트가 없으니
 * 관리자 전용 경로에 실제로 닿는지로 확인한다: 200 이면 ADMIN, 403 이면 일반 사용자.
 */
export async function login(username, password) {
  // btoa 는 문자 코드가 255 를 넘으면 던진다 — 한글이 섞인 비밀번호에서 바로 터진다.
  // UTF-8 로 먼저 바이트를 만든 뒤 base64 로 옮긴다. (deprecated 된 unescape 를 쓰지 않는다)
  const bytes = new TextEncoder().encode(`${username}:${password}`);
  const basic = btoa(String.fromCharCode(...bytes));
  const auth = { Authorization: `Basic ${basic}` };

  let probe;
  try {
    probe = await fetch('/api/characters', { headers: auth });
  } catch {
    return { ok: false, error: BACKEND_DOWN };
  }

  if (probe.status === 401) return { ok: false, error: '계정 또는 비밀번호가 맞지 않습니다.' };
  if (probe.status === 502 || probe.status === 503 || probe.status === 504) {
    return { ok: false, error: BACKEND_DOWN };
  }
  if (!probe.ok) return { ok: false, error: `로그인 확인에 실패했습니다 (HTTP ${probe.status}).` };

  // 여기까지 왔으면 자격증명은 유효하다. 이제 관리자인지만 더 본다.
  // 403 은 실패가 아니라 "일반 사용자"라는 답이다.
  let admin = false;
  try {
    const roleProbe = await fetch('/api/admin/tickets/pending', { headers: auth });
    admin = roleProbe.ok;
  } catch {
    /* 역할 확인이 실패해도 로그인 자체는 성립한다 — 일반 사용자로 둔다 */
  }

  write({ username, basic, admin });
  return { ok: true, admin };
}

/**
 * 인증 헤더를 붙여 주는 fetch.
 *
 * 화면 컴포넌트가 직접 헤더를 만들지 않게 한다 — 한 군데라도 빠뜨리면
 * 그 요청만 401 이 나고, 원인이 화면에서는 "가끔 안 된다"로만 보인다.
 *
 * 401 이 오면 보관 중인 자격증명을 버린다. 서버가 거절한 값을 계속 들고 있으면
 * 사용자는 로그인된 화면을 보면서 모든 동작이 실패하는 상태에 갇힌다.
 */
export async function apiFetch(path, init = {}) {
  const cred = read();
  const headers = new Headers(init.headers ?? {});
  if (cred) headers.set('Authorization', `Basic ${cred.basic}`);

  const res = await fetch(path, { ...init, headers });

  if (res.status === 401 && cred) clear();
  return res;
}

export const BACKEND_DOWN =
  '백엔드(localhost:8081)에 연결할 수 없습니다. ./gradlew bootRun 으로 먼저 띄워 주세요.';
