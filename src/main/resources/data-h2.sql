-- 시드 데이터 (H2 · local 프로파일과 테스트)
-- 스키마가 매번 새로 만들어지므로(create-drop) 중복 방지 구문이 필요 없다.

INSERT INTO orders (id, owner_id, item, status, eta, ordered_at, cost) VALUES
  ('12345', 'user1', '무선 이어폰',   'SHIPPING',  DATE '2026-08-23', DATE '2026-08-19', 42000),
  ('12346', 'user1', '기계식 키보드', 'DELIVERED', DATE '2026-08-18', DATE '2026-08-14', 89000),
  ('12347', 'user1', '노트북 거치대', 'PREPARING', DATE '2026-08-25', DATE '2026-08-20', 23000),
  ('99999', 'user2', '커피 원두 1kg', 'DELIVERED', DATE '2026-08-15', DATE '2026-08-11', 31000);
