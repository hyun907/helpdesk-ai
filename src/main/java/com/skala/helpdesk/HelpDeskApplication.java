package com.skala.helpdesk;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * @SpringBootApplication 은 세 어노테이션을 합친 것이다.
 *   @Configuration + @ComponentScan + @EnableAutoConfiguration
 * 이 클래스가 있는 패키지(com.skala.helpdesk) 아래만 스캔한다. 위치가 곧 범위다.
 */
@SpringBootApplication
public class HelpDeskApplication {

    public static void main(String[] args) {
        SpringApplication.run(HelpDeskApplication.class, args);
    }
}
