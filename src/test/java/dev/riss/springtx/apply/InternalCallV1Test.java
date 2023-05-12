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
 * 트랜잭션 AOP 주의 사항 - 프록시 내부 호출 1
 *
 * 프록시 방식의 AOP 한계
 * => @Transactional 을 사용하는 트랜잭션 AOP 는 프록시를 사용. 프록시 사용 시 메서드 내부 호출에 프록시 적용 불가능
 *    => @Transactional 이 걸린 클래스의 메서드(non-tx)에서 자신 클래스 내부 다른 메서드(tx)를 호출할 때, 트랜잭션이 적용되지 않음
 *
 * 해결 방법
 * 1. internal() 메서드를 별도의 클래스로 분리 - 간단
 * 2. @Transactional 이 붙은 메서드 앞 뒤로 Tx 시작, Tx 종료 로직을 넣게 하는 설정(컴파일 혹은 로드 시점) - 너무 복잡하고 어려움
 *    (스프링 고급편에서 배움)
 */
@Slf4j
@SpringBootTest
public class InternalCallV1Test {

    @Autowired CallService service;

    @Test
    void printProxy () {
        log.info("callService class={}", service.getClass());
    }

    /**
     * 0. 클라이언트가 callService.internal() 호출. 이때 callService 는 트랜잭션 AOP 프록시임
     * 1. callService 의 트랜잭션 AOP 프록시가 호출
     * 2. internal() 메서드에 @Transactional 이 붙어 있으므로 트랜잭션 프록시는 트랜잭션을 적용
     * 3. Tx 적용 후, 실제 callService 객체 인스턴스의 internal() 메서드 호출
     * 4. 실제 callService 가 처리 후 응답 -> 트랜잭션 프록시로 리턴됨 -> 트랜잭션 프록시는 트랜잭션을 완료(커밋 or 롤백)
     */
    @Test
    void internalCall () {
        service.internal();
    }

    /**
     * 0. 클라이언트가 callService.external() 호출. 이때 callService 는 트랜잭션 AOP 프록시임
     * 1. callService 의 트랜잭션 AOP 프록시가 호출
     * 2. external() 메서드에 @Transactional 이 없으므로 트랜잭션 프록시는 트랜잭션 적용되지 않음
     * 3. 바로 실제 callService 인스턴스의 external() 메서드 호출
     * 4. external() 내부에서 @Transactional 이 붙어있는 internal() 호출 (this.internal() => 나 자신 인스턴스의 메서드 호출)
     * => (트랜잭션 관련 코드가 있는)프록시를 거치지 않고, 그냥 내 자신 내부의 메서드를 바로 '내부 호출'한 것임. ==> 트랜잭션이 적용 X
     */
    @Test
    void externalCall () {
        service.external();
    }

    @TestConfiguration
    static class InternalCallV1TestConfig {

        @Bean
        CallService callService() {
            return new CallService();
        }
    }

    @Slf4j
    static class CallService {

        public void external () {
            log.info("call external");
            printTxInfo();      // this.printTxInfo();
            internal();     // 자바에서는 this.internal() 을 뜻함
        }

        /**
         * 트랜잭션이 필요한 영역
         */
        @Transactional
        public void internal () {
            log.info("call internal");
            printTxInfo();
        }

        private void printTxInfo () {
            boolean txActive = TransactionSynchronizationManager.isActualTransactionActive();
            log.info("tx active={}", txActive);
            boolean readOnly = TransactionSynchronizationManager.isCurrentTransactionReadOnly();
            log.info("tx readOnly={}", readOnly);
        }
    }

}
