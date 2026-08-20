-- 시드 데이터 — 권한 격리 검증을 위해 소유자를 갈라 둔다
--   12345 · 12346 · 12347 → user1 소유
--   99999                 → user2 소유  (권한 격리 검증용)
-- ON CONFLICT DO NOTHING : 재기동해도 중복 입력되지 않는다

INSERT INTO orders (id, owner_id, item, status, eta, ordered_at, cost) VALUES
  ('12345', 'user1', '무선 이어폰',      'SHIPPING',  DATE '2026-08-23', DATE '2026-08-19', 42000),
  ('12346', 'user1', '기계식 키보드',    'DELIVERED', DATE '2026-08-18', DATE '2026-08-14', 89000),
  ('12347', 'user1', '노트북 거치대',    'PREPARING', DATE '2026-08-25', DATE '2026-08-20', 23000),
  ('99999', 'user2', '커피 원두 1kg',    'DELIVERED', DATE '2026-08-15', DATE '2026-08-11', 31000)
ON CONFLICT (id) DO NOTHING;
