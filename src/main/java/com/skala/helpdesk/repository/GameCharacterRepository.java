package com.skala.helpdesk.repository;

import com.skala.helpdesk.domain.GameCharacter;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 권한 조건은 쿼리 자체에 넣는다.
 * findById 로 꺼낸 뒤 자바에서 소유자를 비교하는 코드는 위험하다 —
 * 조건을 쿼리에 넣어야 실수로 빠뜨릴 여지가 없다.
 */
public interface GameCharacterRepository extends JpaRepository<GameCharacter, String> {

    // 소유자 조건을 쿼리에 넣는다 — 이 한 줄이 권한 경계다
    Optional<GameCharacter> findByIdAndOwnerId(String id, String ownerId);

    List<GameCharacter> findByOwnerIdOrderByLevelDesc(String ownerId);
}
