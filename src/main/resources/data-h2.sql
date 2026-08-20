-- 시드 데이터 (H2 · local 프로파일과 테스트)
-- 스키마가 매번 새로 만들어지므로(create-drop) 중복 방지 구문이 필요 없다.
--   CH-1001 · CH-1002 → player1
--   CH-9001           → player2   (남의 캐릭터 조회 차단 검증용)

INSERT INTO game_characters (id, owner_id, nickname, job, level, server, last_played_at) VALUES
  ('CH-1001', 'player1', '달빛기사',   '전사',     87, '아스가르드', TIMESTAMP '2026-08-19 23:14:00'),
  ('CH-1002', 'player1', '은하수',     '마법사',   64, '아스가르드', TIMESTAMP '2026-08-17 21:02:00'),
  ('CH-9001', 'player2', '폭풍검객',   '검성',     92, '미드가르드', TIMESTAMP '2026-08-20 01:47:00')
;

INSERT INTO inventory_items (id, character_id, item_name, grade, quantity, acquired_at) VALUES
  ('IT-0001', 'CH-1001', '달빛 대검',       'LEGEND', 1,  TIMESTAMP '2026-07-02 20:11:00'),
  ('IT-0002', 'CH-1001', '수호자의 갑옷',   'HERO',   1,  TIMESTAMP '2026-07-18 19:40:00'),
  ('IT-0003', 'CH-1001', '상급 회복 물약',  'COMMON', 42, TIMESTAMP '2026-08-15 12:00:00'),
  ('IT-0004', 'CH-1002', '별빛 지팡이',     'RARE',   1,  TIMESTAMP '2026-08-01 18:22:00'),
  ('IT-0005', 'CH-9001', '폭풍의 인장',     'LEGEND', 1,  TIMESTAMP '2026-06-11 22:05:00')
;

INSERT INTO sanctions (id, owner_id, type, reason, started_at, ends_at) VALUES
  ('SC-2001', 'player1', 'CHAT_BLOCK', '거래 채널에서 반복 도배',   TIMESTAMP '2026-08-10 14:30:00', TIMESTAMP '2026-08-13 14:30:00'),
  ('SC-2002', 'player2', 'SUSPENSION', '비정상 프로그램 사용 의심', TIMESTAMP '2026-08-18 09:00:00', TIMESTAMP '2026-08-25 09:00:00')
;
