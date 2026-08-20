package com.skala.helpdesk.service;

import com.skala.helpdesk.domain.GameCharacter;
import com.skala.helpdesk.repository.GameCharacterRepository;
import com.skala.helpdesk.repository.InventoryItemRepository;
import com.skala.helpdesk.repository.SanctionRepository;
import com.skala.helpdesk.web.CharacterNotFoundException;
import com.skala.helpdesk.web.dto.CharacterResponse;
import com.skala.helpdesk.web.dto.InventoryItemResponse;
import com.skala.helpdesk.web.dto.SanctionResponse;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 업무 흐름과 트랜잭션 경계. 클래스 기본값은 조회(readOnly), 쓰기에서만 재정의한다.
 * Phase 4 의 도구도 여기와 같은 소유자 검증 규칙을 쓴다.
 */
@Service
@Transactional(readOnly = true)
public class CharacterService {

    private final GameCharacterRepository characters;
    private final InventoryItemRepository items;
    private final SanctionRepository sanctions;

    public CharacterService(GameCharacterRepository characters,
                            InventoryItemRepository items,
                            SanctionRepository sanctions) {
        this.characters = characters;
        this.items = items;
        this.sanctions = sanctions;
    }

    public CharacterResponse find(String characterId, String ownerId) {
        return CharacterResponse.from(mustOwn(characterId, ownerId));
    }

    public List<CharacterResponse> findMine(String ownerId) {
        return characters.findByOwnerIdOrderByLevelDesc(ownerId)
                .stream().map(CharacterResponse::from).toList();
    }

    public List<InventoryItemResponse> inventory(String characterId, String ownerId) {
        mustOwn(characterId, ownerId);          // 인벤토리도 캐릭터 소유자만 본다
        return items.findByCharacterIdOrderByGradeDesc(characterId)
                .stream().map(InventoryItemResponse::from).toList();
    }

    public List<SanctionResponse> sanctionHistory(String ownerId) {
        return sanctions.findByOwnerIdOrderByStartedAtDesc(ownerId)
                .stream().map(SanctionResponse::from).toList();
    }

    /** 소유자 조건을 쿼리에 걸어 조회한다. 없으면 '없는 캐릭터'와 같은 예외다. */
    private GameCharacter mustOwn(String characterId, String ownerId) {
        return characters.findByIdAndOwnerId(characterId, ownerId)
                .orElseThrow(() -> new CharacterNotFoundException(characterId));
    }
}
