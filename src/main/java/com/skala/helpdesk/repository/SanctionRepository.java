package com.skala.helpdesk.repository;

import com.skala.helpdesk.domain.Sanction;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SanctionRepository extends JpaRepository<Sanction, String> {

    // 제재 이력도 계정 경계를 넘지 않는다
    List<Sanction> findByOwnerIdOrderByStartedAtDesc(String ownerId);

    Optional<Sanction> findByIdAndOwnerId(String id, String ownerId);
}
