// @ts-check
import { defineConfig } from 'astro/config';

import react from '@astrojs/react';

export default defineConfig({
  // 정적 산출 — Node 런타임을 추가하지 않는다. 백엔드는 Spring Boot 하나로 유지한다.
  output: 'static',

  // 산출물은 frontend/dist 안에 가둔다.
  // src/main/resources/static 으로 내보내는 연결은 백엔드가 커밋·안정된 뒤에 붙인다.
  // 지금 그쪽 트리에 파일을 떨어뜨리면 백엔드 세션의 git status 가 오염된다.
  outDir: './dist',

  server: { port: 4321 },

  vite: {
    server: {
      proxy: {
        // 개발 중 API 는 Spring(8081) 으로 넘긴다 → Spring 쪽 CORS 설정이 필요 없다.
        '/api': {
          // 기본은 백엔드 개발 포트. 목 서버로 화면만 확인할 때 바꿔 끼운다:
          //   API_TARGET=http://localhost:8099 npm run dev
          target: process.env.API_TARGET ?? 'http://localhost:8081',
          changeOrigin: true,

          /**
           * 개발 전용 인증 주입.
           *
           * 백엔드가 /api/admin/** 에 ROLE_ADMIN 을 걸어 두었다. 브라우저에서 그냥 부르면
           * 401 + WWW-Authenticate 로 크롬 기본 로그인 팝업이 뜬다 — 화면이 멈춘다.
           *
           * 자격증명은 환경변수에서만 읽는다. 소스에 넣지 않는다:
           *   ADMIN_BASIC='<계정>:<비밀번호>' npm run dev
           *
           * 이 코드는 dev 서버에서만 돈다. 빌드 산출물에는 포함되지 않는다.
           * 운영에서는 사용자가 직접 로그인해야 하며, 그 흐름은 별도로 만든다.
           */
          configure(proxy) {
            /**
             * 업스트림(Spring)이 안 떠 있을 때 Vite 기본 동작은 500 이다.
             * 그러면 화면에는 "서버 내부 오류"로 보이고, 있지도 않은 Spring 로그를 뒤지게 된다.
             * 연결 자체가 안 된 것이므로 502 로 내려 준다 — 화면이 원인을 정확히 말할 수 있다.
             */
            proxy.on('error', (_err, _req, res) => {
              if (res && 'writeHead' in res && !res.headersSent) {
                res.writeHead(502, { 'Content-Type': 'application/json; charset=utf-8' });
                res.end(JSON.stringify({ message: 'upstream unreachable' }));
              }
            });

            /**
             * 경로별로 다른 계정을 붙인다. 관리 창구와 상담 창구는 권한이 다르다:
             *   ADMIN_BASIC → /api/admin/**  (ROLE_ADMIN 계정)
             *   USER_BASIC  → /api/chat/**   (일반 사용자 계정)
             *
             * 값은 백엔드 SecurityConfig 의 실습 계정을 쓴다. 여기에 적지 않는다.
             *
             * 둘 다 환경변수에서만 읽는다. 저장소에 비밀번호를 넣지 않는다.
             * 이 코드는 dev 서버에서만 돈다 — 빌드 산출물에는 들어가지 않는다.
             */
            const accounts = [
              ['/api/admin', process.env.ADMIN_BASIC],
              ['/api/chat', process.env.USER_BASIC],
            ]
              .filter(([, cred]) => cred)
              .map(([prefix, cred]) => [prefix, Buffer.from(cred).toString('base64')]);

            if (accounts.length === 0) return;

            proxy.on('proxyReq', (proxyReq, req) => {
              if (proxyReq.getHeader('authorization')) return;
              const hit = accounts.find(([prefix]) => req.url?.startsWith(prefix));
              if (hit) proxyReq.setHeader('authorization', `Basic ${hit[1]}`);
            });
          },
        },
      },
    },
  },

  integrations: [react()],
});