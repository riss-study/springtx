package dev.riss.springtx.apply;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 트랜잭션 AOP 주의 사항 - 프록시 내부 호출 2
 *
 * InternalCallV1Test 에서의 한계였던 AOP 프록시 내부 호출 트랜잭션 미적용 문제를
 * internal() 메서드를 별도의 클래스로 분리하여 해결
 *
 * (스프링 3.0 미만 버젼만)
 * 주의!! public 메서드만 트랜잭션 적용
 * => 스프링의 트랜잭션 AOP 기능은 public 메서드에만 Tx을 적용하도록 기본 설정돼있음
 *    => protected, private, package-visible(아무 설정 안된 것, default 접근 제한자) 에는 Tx 적용 안됨.
 *    => protected, package-visible 은 외부 호출이 가능하지만 스프링이 막아둠
 *    (프록시의 내부 호출과는 무관)
 *
 *    Why only public?
 *    => 보통 클래스 레벨에서 @Transactional 을 적용 많이 함.
 *       => 막아두지 않으면 트랜잭션을 의도하지 않는 곳까지 트랜잭션이 과도하게 적용될 수 있음
 *       => 보통 트랜잭션은 비즈니스 로직의 시작점에서 걸어주므로, 대부분 외부에서 열어준 곳을 시작점으로 사용
 *
 * 더더더더 주의!! "스프링 3.0 이상"부터는 protected, package-visible 에서도 트랜잭션 적용됨 (private 만 안됨)
 */
@Slf4j
@SpringBootTest
public class InternalCallV2Test {

    @Autowired ExternalService externalService;

    /**
     * 0. 클라이언트는 externalService.external() 호출
     * 1. externalService 는 실제 externalService 객체 인스턴스
     *    (ExternalService 클래스 내부에 @Transactional 이 붙은 메서드가 없음, 프록시가 붙을만한 이유 없음(데이터접근 예외 추상화 등))
     * 2. externalService 는 주입받은 internalService.internal() 호출
     * 3. internalService 는 트랜잭션 AOP 프록시 (내부에 @Transactional 있음). internal() 메서드에 @Transactional 이 있으므로
     *    트랜잭션 프록시는 트랜잭션을 적용
     * 4. Tx 적용 후, 실제 internalService 객체 인스턴스의 internal() 메서드 호출
     */
    @Test
    void externalCallV2 () {
        log.info("externalService class={}", externalService.getClass());
        externalService.external();
    }

    @TestConfiguration
    static class InternalCallV1TestConfig {

        @Bean
        ExternalService externalService() {
            return new ExternalService(internalService());
        }

        @Bean
        InternalService internalService() {
            return new InternalService();
        }
    }

    @Slf4j
    @RequiredArgsConstructor
    static class ExternalService {

        private final InternalService internalService;

        public void external () {
            log.info("call external");
            printTxInfo();
            internalService.internal();
        }

        private void printTxInfo () {
            boolean txActive = TransactionSynchronizationManager.isActualTransactionActive();
            log.info("tx active={}", txActive);
            boolean readOnly = TransactionSynchronizationManager.isCurrentTransactionReadOnly();
            log.info("tx readOnly={}", readOnly);
        }

    }

    @Slf4j
    static class InternalService {
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
