import { useEffect } from 'react';
import { clear } from '../lib/auth.js';
import { useAuth } from './Guard.jsx';

/**
 * 헤더의 로그인 배지.
 *
 * 누구로 로그인해 있는지를 항상 보이게 둔다. 이 화면은 계정에 따라 보이는 것이
 * 달라지는데(관리 메뉴·소유 캐릭터), 지금 누구인지 모르면 "왜 안 보이지"의 답을
 * 찾을 수가 없다.
 *
 * 역할을 body 의 data 속성으로 올려 둔다 — 메뉴는 정적 HTML 로 나가므로
 * 서버에서 역할을 알 수 없다. 관리 메뉴를 감추는 판단은 CSS 가 여기 붙은 값을 보고 한다.
 */
export default function SessionBadge() {
  const { cred, ready } = useAuth();

  useEffect(() => {
    if (!ready) return;
    const role = !cred ? 'anon' : cred.admin ? 'admin' : 'user';
    document.body.dataset.role = role;
  }, [cred, ready]);

  // 확인 전에는 아무것도 그리지 않는다. "로그인" 이 잠깐 떴다가 계정명으로 바뀌면
  // 헤더가 한 번 튀고, 그게 매 페이지 이동마다 반복된다.
  if (!ready) return null;

  if (!cred) return <span className="session-badge anon">로그인 안 됨</span>;

  return (
    <span className="session-badge">
      <span className="who">
        {cred.username}
        {cred.admin && <span className="role">GM</span>}
      </span>
      <button type="button" onClick={clear}>
        로그아웃
      </button>
    </span>
  );
}
