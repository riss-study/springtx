package dev.riss.springtx.apply;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 트랜잭션 AOP 주의 사항 - 스프링 초기화 시점
 *
 * 초기화 코드 (ex. @PostConstruct) 와 @Transactional 을 함께 사용하면 Tx 적용 안됨
 * why? => 순서: 초기화 코드 먼저 호출 -> 트랜잭션 AOP 적용
 *          => 초기화 시점에 해당 메서드의 트랜잭션 획득 불가능함
 *
 * then? 대안: ApplicationReadyEvent 이벤트 사용 (스프링 모든 초기화(컨테이너 생성, AOP 생성 등) 된 후에 호출되는 이벤트 리스너)
 */

@Slf4j
@SpringBootTest
public class InitTxTest {

    @Autowired Hello hello;

    @Test
    public void go () {
        // 초기화 코드는 스프링 초기화 시점에 호출됨
    }

    @TestConfiguration
    static class InitTxTestConfig {

        @Bean
        Hello hello() {
            return new Hello();
        }
    }

    static class Hello {

        @PostConstruct  // Tx 적용 없이 그냥 일반적인 초기화하려면 이걸 사용하면 됨
        @Transactional
        public void initV1 () {
            boolean isTxActive = TransactionSynchronizationManager.isActualTransactionActive();
            log.info("Hello initV1 @PostConstruct tx isActive={}", isTxActive);
        }

        /**
         * 스프링 떴을 때 후반부 로그 순서
         * Hello initV1 @PostConstruct tx isActive=false
         * Started InitTxTest in 1.753 seconds (process running for 2.552)
         * Getting transaction for [dev.riss.springtx.apply.InitTxTest$Hello.initV2]
         * Hello initV2 ApplicationReadyEvent tx isActive=true
         */
        @EventListener(ApplicationReadyEvent.class) // 트랜잭션, bean 등 모든 스프링이 완성되고, 스프링이 떴을 때 호출
        @Transactional
        public void initV2 () {
            boolean isTxActive = TransactionSynchronizationManager.isActualTransactionActive();
            log.info("Hello initV2 ApplicationReadyEvent tx isActive={}", isTxActive);
        }
    }

}
