package dev.riss.springtx.apply;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Transaction 적용 위치
 *
 * 스프링에서의 우선순위 - 항상 더 구체적이고 자세한 것이 높은 우선순위
 *
 * @Transcational 은 인터페이스에서도 적용 가능
 * (권장하지 않음. 실제로 잘 쓰진 않음. 간혹 인터페이스에 애노테이션 두면 AOP 적용 안되는 경우도 있음)
 * (실제로 스프링 5.0 미만에서는 구체 클래스 기반으로 프록시를 생성하는 CGLIB 방식을 사용하면 인터페이스에 있는 @Transactional 인식 못했음)
 * (스프링 5.0 이상에서는 개선됐으나, 다른 AOP 방식에서 또 적용되지 않을 가능성이 있으므로 가급적이면 구체 클래스에 @Transactional 사용 추천)
 *
 * @Transactional 적용 우선순위
 * 1. 클래스의 메서드 (우선순위 가장 높음)
 * 2. 클래스의 타입
 * 3. 인터페이스의 메서드
 * 4. 인터페이스의 타입 (우선순위 가장 낮음)
 */

@SpringBootTest
public class TxLevelTest {

    @Autowired LevelService service;

    @Test
    void orderTest () {
        service.write();
        service.read();
    }

    @TestConfiguration
    static class TxLevelTestConfig {
        @Bean
        LevelService levelService () {
            return new LevelService();
        }
    }

    @Slf4j
    @Transactional(readOnly = true)
    static class LevelService {

        // default ==> readonly=false 이므로 생략 가능
        @Transactional//(readOnly = false)        // readOnly=false (우선 순위 method -> class level => method level 적용)
        public void write () {
            log.info("call write");
            printTxInfo();      // tv active=true, tx readOnly=false
        }

        public void read () {               // readOnly=true (method level 엔 없고, class level 에 붙어있음 => class level 적용)
            log.info("call read");
            printTxInfo();      // tx active=true, tx readOnly=true
        }

        private void printTxInfo () {
            boolean isTxActive = TransactionSynchronizationManager.isActualTransactionActive();
            log.info("ts active={}", isTxActive);

            boolean isReadOnly = TransactionSynchronizationManager.isCurrentTransactionReadOnly();
            log.info("tx readOnly={}", isReadOnly);
        }
    }

}
