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

          },
        },
      },
    },
  },

  integrations: [react()],
});