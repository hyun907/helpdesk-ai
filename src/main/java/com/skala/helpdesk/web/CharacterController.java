package com.skala.helpdesk.web;

import com.skala.helpdesk.service.CharacterService;
import com.skala.helpdesk.web.dto.CharacterResponse;
import com.skala.helpdesk.web.dto.InventoryItemResponse;
import com.skala.helpdesk.web.dto.SanctionResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 받고 · 검증하고 · 서비스에 넘기고 · 응답 형태로 돌려준다.
 * 업무 규칙을 넣지 않는다. 그리고 이 파일에는 ChatClient 가 없다.
 */
@RestController
@RequestMapping("/api/characters")
@Tag(name = "캐릭터", description = "캐릭터 · 인벤토리 · 제재 이력 조회")
public class CharacterController {

    private final CharacterService characterService;

    public CharacterController(CharacterService characterService) {
        this.characterService = characterService;
    }

    @GetMapping("/{characterId}")
    @Operation(summary = "캐릭터 단건 조회",
               description = "소유자 조건을 쿼리 안에서 함께 건다. 남의 캐릭터는 '없는 것'으로 응답한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "404", description = "없거나 남의 캐릭터")})
    public CharacterResponse find(
            @Parameter(description = "캐릭터 ID", example = "CH-1001") @PathVariable String characterId,
            @Parameter(description = "계정 ID", example = "player1") @RequestParam String ownerId) {
        return characterService.find(characterId, ownerId);
    }

    @GetMapping
    @Operation(summary = "내 캐릭터 목록")
    public List<CharacterResponse> findMine(
            @Parameter(description = "계정 ID", example = "player1") @RequestParam String ownerId) {
        return characterService.findMine(ownerId);
    }

    @GetMapping("/{characterId}/inventory")
    @Operation(summary = "인벤토리 조회", description = "캐릭터 소유자만 볼 수 있다.")
    public List<InventoryItemResponse> inventory(
            @Parameter(description = "캐릭터 ID", example = "CH-1001") @PathVariable String characterId,
            @Parameter(description = "계정 ID", example = "player1") @RequestParam String ownerId) {
        return characterService.inventory(characterId, ownerId);
    }

    @GetMapping("/sanctions")
    @Operation(summary = "내 제재 이력", description = "계정 단위로 조회한다.")
    public List<SanctionResponse> sanctions(
            @Parameter(description = "계정 ID", example = "player2") @RequestParam String ownerId) {
        return characterService.sanctionHistory(ownerId);
    }
}
