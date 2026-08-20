package com.skala.helpdesk.web;

import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 인증·인가의 단일 지점.
 *
 * 여기서 막지 못한 요청은 그대로 도구까지 흘러 들어간다.
 * 모델은 사용자를 대신해 행동하므로, "누가 요청했는가"가 서버에서 확정되지 않으면
 * 도구 안에서 하는 소유자 검증도 근거를 잃는다 — 프롬프트에 실린 계정 ID 는 사용자가 지어낼 수 있다.
 *
 * Spring Boot 4.1 / Spring Security 7.1 기준이다.
 * 7.x 에서는 람다 없는 설정 메서드가 모두 사라졌다. httpBasic(), csrf() 같은 무인자 호출은 컴파일되지 않는다.
 */
@Configuration
@EnableWebSecurity
// @PreAuthorize 를 켠다 — 승인 API 의 관문이 이 한 줄에 달려 있다.
// 이게 빠지면 @PreAuthorize 는 아무 일도 하지 않고 조용히 통과한다. 실패가 눈에 띄지 않는 종류의 사고다.
@EnableMethodSecurity
public class SecurityConfig {

    /** springdoc 이 여는 경로들 — 운영에서는 통째로 닫는다. */
    private static final String[] SWAGGER_PATHS = {
            "/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs", "/v3/api-docs/**"
    };

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, Environment env) throws Exception {
        boolean prod = env.acceptsProfiles(Profiles.of("prod"));

        http
                // CSRF 비활성 — 이 서버는 세션 쿠키가 아니라 요청마다 실리는 Basic 자격증명으로 인증한다.
                // 브라우저가 자동으로 붙여 주는 자격증명이 없으면 교차 사이트 요청 위조가 성립하지 않는다.
                // 반대로 말하면, 나중에 쿠키·세션 로그인을 도입하는 순간 이 줄은 다시 켜야 한다.
                .csrf(csrf -> csrf.disable())

                // REST API 는 상태를 서버에 남기지 않는다. 세션을 만들면 확장할 때마다 세션 공유 문제를 떠안는다.
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                .authorizeHttpRequests(auth -> {
                    // 규칙은 먼저 등록된 것이 먼저 매칭된다. 좁은 규칙을 위에 둔다.

                    // 운영에서 API 문서는 공격자에게 주는 지도다. 엔드포인트·파라미터·예시값이 한 페이지에 정리되어 있다.
                    // application-prod.yml 에서 springdoc 자체를 끄지만, 설정 한 줄이 되돌려지는 사고를 대비해 여기서도 막는다.
                    if (prod) {
                        auth.requestMatchers(SWAGGER_PATHS).denyAll();
                    } else {
                        auth.requestMatchers(SWAGGER_PATHS).permitAll();
                    }

                    auth
                            // 인제스트는 벡터 스토어의 내용을 바꾼다 — 답변의 근거를 바꾸는 행위다
                            .requestMatchers("/api/admin/**").hasRole("ADMIN")
                            // actuator 는 설정·환경·메트릭을 드러낸다. health 하나만 열고 싶어질 때가 오겠지만, 기본은 닫는다
                            .requestMatchers("/actuator/**").hasRole("ADMIN")
                            // 대화는 로그인한 사용자만 — 인증된 이름이 있어야 도구가 "누구의 데이터인지" 판단할 수 있다
                            .requestMatchers("/api/chat/**").authenticated()
                            // 남는 경로는 전부 막는다. 화이트리스트가 아니라 블랙리스트로 운영하면
                            // 새로 추가한 컨트롤러가 아무도 모르게 열린 채로 배포된다.
                            .anyRequest().authenticated();
                })

                // 실습 범위에서는 HTTP Basic 으로 충분하다.
                // 폼 로그인을 켜지 않는 이유: 인증 실패에 302 리다이렉트가 아니라 401 이 나와야 API 클라이언트가 알아듣는다.
                // 다만 Basic 은 자격증명을 매 요청 평문에 가깝게 싣는다 — HTTPS 없이 운영에 올리면 안 된다.
                .httpBasic(Customizer.withDefaults());

        return http.build();
    }

    /**
     * 인메모리 사용자 — 실습용이다.
     *
     * 운영에서는 절대 이러면 안 된다.
     * 비밀번호가 소스에 박혀 있으면 저장소를 읽을 수 있는 모든 사람이 계정을 가진 것과 같고,
     * git 이력에 한 번 들어간 비밀번호는 나중에 지워도 이미 퍼진 뒤다.
     * 실제로는 사용자 저장소(DB·LDAP·IdP)를 붙이고 비밀번호는 해시로만 보관한다.
     */
    @Bean
    public InMemoryUserDetailsManager userDetailsService(PasswordEncoder encoder) {
        UserDetails player1 = User.withUsername("player1")
                .password(encoder.encode("player1-pw"))     // 실습용 고정 비밀번호
                .roles("USER")
                .build();

        UserDetails player2 = User.withUsername("player2")
                .password(encoder.encode("player2-pw"))
                .roles("USER")
                .build();

        // GM 계정. 승인·반려와 관리 창구는 이 역할만 통과한다.
        UserDetails gm = User.withUsername("gm")
                .password(encoder.encode("gm-pw"))
                .roles("ADMIN")
                .build();

        return new InMemoryUserDetailsManager(List.of(player1, player2, gm));
    }

    /**
     * 위임 인코더 — 저장된 해시 앞에 {bcrypt} 같은 식별자가 붙는다.
     * 나중에 알고리즘을 바꿔도 기존 비밀번호를 한꺼번에 버리지 않아도 된다.
     * 평문 보관({noop})은 쓰지 않는다. 로그·힙덤프·백업 어디로든 그대로 새어 나간다.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
}
