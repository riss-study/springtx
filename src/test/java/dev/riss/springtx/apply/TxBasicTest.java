package dev.riss.springtx.apply;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Transaction 적용 확인 Test
 *
 * @Transaction 애노테이션이 특정 클래스나 메서드에 하나라도 있으면, 트랜잭션 AOP 는 프록시를 만들어서 스프링 컨테이너에 등록
 * 클라이언트 txBasicTest 는 스프링컨테이너에 @Autowired BasicService basicService 로 의존관계 주입을 요청
 * -> 스프링 컨테이너는 실제 객체 대신 프록시가 스프링 빈으로 등록돼있기 때문에, 프록시를 주입
 * => 프록시는 BasicService 를 상속해서 만들어지기 때문에 다형성 활용 가능
 *    => BasicService 대신프록시인 BasicService$$SpringCGLIB 주입 가능
 *
 */
@Slf4j
@SpringBootTest
public class TxBasicTest {

    @Autowired
    BasicService basicService;

    @Test
    void proxyCheck () {
        // 여기서는 실제 'basicService' 객체 대신 프록시인 'BasicService$$SpringCGLIB$$0' 가 등록돼었고 출력됨
        log.info("aop class={}", basicService.getClass());
        boolean isAopProxy = AopUtils.isAopProxy(basicService);
        assertThat(isAopProxy).isTrue();
    }

    @Test
    void txTest () {
        basicService.tx();
        basicService.nonTx();
    }

    @TestConfiguration
    static class TxApplyBasicConfig {

        @Bean
        BasicService basicService () {
            return new BasicService();
        }
    }

    @Slf4j
    static class BasicService {

        /**
         * Client 가 basicService.tx() 호출 -> AOP Proxy 의 tx() 가 호출 -> tx() 가 트랜잭션 사용할 수 있는지 체크
         * -> tx() 에 @Transactional 이 붙어있으므로 트랜잭션 적용 대상임
         * -> 트랜잭션 시작 후, 실제 basicService.tx() 호출
         * -> 실제 basicService.tx() 호출 끝나고 프록시로 제어가 돌아오면(리턴), 프록시는 트랜잭션 로직을 커밋 or 롤백 후 트랜잭션 종료
         */
        @Transactional
        public void tx () {

            log.info("call tx");
            // (내부적으로 쓰레드로컬이 들어있기 때문에 가능) 현재 쓰레드에 트랜잭션이 적용돼있는지 유무 리턴 함수, true: tx 적용 o
            boolean isTxActive = TransactionSynchronizationManager.isActualTransactionActive();
            log.info("tx active={}", isTxActive);
        }

        /**
         * Client 가 basicService.nonTx() 호출 -> AOP Proxy 의 nonTx() 호출 -> tx() 가 트랜잭션 사용할 수 있는지 체크
         * -> tx() 에 @Transactional 이 안붙어있으므로(클래트 or 메서드 level) 트랜잭션 적용 대상 아님
         * -> 실제 basicService.tx() 호출 후 종료
         */
        public void nonTx () {

            log.info("call nonTx");
            boolean isTxActive = TransactionSynchronizationManager.isActualTransactionActive();
            log.info("tx active={}", isTxActive);
        }
    }

}
