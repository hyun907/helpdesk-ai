package com.skala.helpdesk.config;

import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * API 문서 설정.
 *
 * <p>보안 스키마를 선언해 두지 않으면 Swagger UI 에 Authorize 버튼이 나타나지 않는다.
 * 그러면 Try it out 으로 보낸 요청에 인증 헤더가 붙지 않아 전부 401 로 떨어지는데,
 * 화면상으로는 API 가 고장난 것처럼 보인다. 실제로 이 프로젝트에서 그 상태였다.
 *
 * <p>운영 프로파일에서는 문서 자체를 닫는다(application-prod.yml). 여기 적힌 계정은
 * 개발용 고정값이므로 문서가 외부에 열리면 그대로 노출된다.
 */
@Configuration
@SecurityScheme(name = "basicAuth", type = SecuritySchemeType.HTTP, scheme = "basic")
public class OpenApiConfig {

    @Bean
    OpenAPI helpDeskOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("HelpDesk AI")
                        .version("0.2")
                        .description("""
                                온라인 게임 고객지원 어시스턴트.

                                **먼저 우측 상단 Authorize 를 눌러 로그인하세요.** 그러지 않으면 모든 요청이 401 입니다.

                                | 계정 | 비밀번호 | 권한 | 쓸 수 있는 것 |
                                |---|---|---|---|
                                | `player1` | `player1-pw` | USER | 캐릭터 CH-1001 · CH-1002, 상담 |
                                | `player2` | `player2-pw` | USER | 캐릭터 CH-9001, 상담 |
                                | `gm` | `gm-pw` | ADMIN | 인제스트 · 청크 조회 · 신청 승인 |

                                ### 처음이라면 이 순서로
                                1. `POST /api/admin/ingest` — 정책 문서를 색인한다 (gm)
                                2. `GET /api/admin/chunks?q=아이템 복구 기한` — 무엇이 검색되는지 점수와 함께 본다 (gm)
                                3. `POST /api/chat` — 상담을 걸어 본다 (player1)

                                ### 권한 격리를 확인하려면
                                `player1` 로 `GET /api/characters/CH-9001` 을 호출한다. CH-9001 은 `player2` 소유이므로
                                **없는 캐릭터와 똑같은 404** 가 나와야 한다. 403 이 나오면 그 캐릭터가 존재한다는 사실이
                                새어 나간 것이다.

                                조회 주체는 Authorize 로 로그인한 계정에서만 온다. 요청에 계정 ID 를 넣는 자리는 없다 —
                                파라미터로 받으면 인증은 `player1` 로 해 두고 다른 계정 ID 를 적어 보내는 것만으로
                                남의 데이터가 열린다.

                                ### 주의
                                `POST /api/chat` 은 모델을 호출하므로 비용이 발생한다.
                                """))
                .addSecurityItem(new SecurityRequirement().addList("basicAuth"));
    }
}
