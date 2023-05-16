package dev.riss.springtx.propagation;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.interceptor.DefaultTransactionAttribute;

import javax.sql.DataSource;

/**
 * <정리>
 * 물리 트랜잭션을 명확하게 분리하려면 => 내부 트랜잭션을 시작할 때 'PROPAGATION_REQUIRES_NEW' 옵션 사용
 * 외부 tx 과 내부 tx 이 각각 별도의 물리 tx 을 가짐
 * 별도의 물리 tx 를 가진다 -> 서로 다른 DB 커넥션을 따로 사용한다는 뜻 => 주의!! 내부 로직 시점에 데이터베이스 커넥션이 동시에 2개 사용됨
 * ==> 트래픽이 많거나 성능이 중요한 경우, 주의해서 사용해야 함. 잘못하면 장애로 이어짐
 * 이 경우 내부 tx 이 롤백되더라도(내부 로직이 롤백되어도), 외부 로직에서 저장한 데이터에는 영향을 주지 않음
 * 최종적으로 내부 로직은 롤백, 외부 로직은 커밋 (내부 tx 롤백, 외부 tx 커밋 한 경우)
 *
 * <<트랜잭션 전파 (외부 - 내부) REQUIRES_NEW 요청 흐름>>
 * <요청흐름>
 *   <외부 tx>
 * 1. 'txManager.getTransaction()' 호출하여 외부 트랜잭션 시작
 * 2. tx Manager 는 dataSource 를 통해(꺼내서) connection 생성 (conn1)
 * 3. 생성한 connection 을 수동 커밋 모드('setAutoCommit(false)') 로 설정 -> 물리 트랜잭션 시작
 * 4. tx manager 는 'TransactionSynchronizationManager' 에 connection 보관
 * 5. tx manager 는 tx 을 생성한 결과를 'TransactionStatus' 에 담아서 반환 -> 여기에 신규 트랜잭션의 여부가 담겨져 있음
 *    => 'isNewTransaction' 을 통해 신규 트랜잭션 여부 확인 가능. tx 를 처음 시작했으므로 신규 tx 임 ('true')
 * 6. 외부 메서드의 로직('로직1')이 사용되고 커넥션이 필요한 경우, 트랜잭션 동기화 매니저를 통해 트랜잭션이 적용된 커넥션(conn1) 획득해서 사용
 *   </외부 tx>
 *   <내부 tx>
 * 7. 'REQUIRES_NEW' 옵션으로 'txManager.getTransaction()' 호출하여 내부 트랜잭션 시작
 *    => 트랜잭션 매니저는 'REQUIRES_NEW' 옵션 확인, 기존 트랜잭션에 참여하는 것이 아닌 새로운 트랜잭션 시작
 * 8. 트랜잭션 매니저는 데이터 소스를 통해 새로운 커넥션 생성 (conn2)
 * 9. 생성한 커넥션(conn2)을 수동 커밋 모드('setAutoCommit(false')로 설정 => 물리 Tx 시작
 * 10. 트랜잭션 매니저는 트랜잭션 동기화 매니저에 커넥션을 보관
 *    => 이때 기존의 커넥션 conn1 은 잠시 보류, 지금부터는 새로운 커넥션(conn2)이 사용 (내부 Tx 가 완료될 때까지 커넥션 conn2 사용)
 * 11. Tx Manager 는 신규 Tx 의 생성한 결과를 반환 => isNewTransaction==true
 * 12. 내부 메서드의 로직('로직2')이 사용되고 커넥션이 필요한 경우, tx sync manager 를 통해 내부 커넥션(conn2)을 획득해서 사용
 *   </내부 tx>
 * </요청흐름>
 *
 * <<트랜잭션 전파 (외부 커밋 - 내부 롤백) REQUIRES_NEW 응답 흐름>>
 * <응답흐름>
 *   <내부 tx>
 * 13. 로직2가 끝나고 트랜잭션 매니저를 통해 내부 트랜잭션 롤백 (로직2에 문제가 있어서 롤백함을 가정)
 * 14. Tx Manager 는 롤백 시점에 신규 트랜잭션 여부에 따라 다르게 동작. 현재 tx 은 신규 Tx(isNewTransaction==true) => 실제 롤백 호출
 * 15. 내부 트랜잭션이 커넥션 conn2 물리 트랜잭션을 롤백
 *     => 내부 Tx 이 종료되고 conn2 는 종료되거나 커넥션 풀에 반납(커넥션 풀 사용유무에 따라), 이때 conn1 재개
 *   </내부 tx>
 *   <외부 tx>
 * 14. 외부 트랜잭션에 커밋 요청
 * 15. 외부 트랜잭션이 신규 트랜잭션임(isNewTransaction==true)을 확인하고 DB 커넥션 conn1 물리 트랜잭션 커밋을 호출
 * 16. 이때 rollbackOnly 설정을 체크. 설정이 없으므로 커밋
 *     => 외부 Tx 이 종료되고, conn1 은 종료되거나 커넥션 풀에 반납
 *   </외부 tx>
 * </응답흐름>
 * 
 * 
 * <전파옵션>
 *     1. REQUIRED: 가장 많이 사용하는 Default. 기존 Tx 없으면 생성, 있으면 참여 => Tx 이 필수라는 의미로 이해
 *     2. REQUIRES_NEW: 항상 새로운 Tx 사용. 기존 tx 유무에 관계없이 생성
 *     3. SUPPORT: 트랜잭션을 지원한다는 뜻. 기존 Tx 없으면 없는 대로 진행, 있으면 참여
 *     4. NOT_SUPPORT: 트랜잭션 지원 X. 기존 Tx 없으면 없이 진행, 있으면 기존 트랜잭션은 보류되고 없이 진행
 *     5. MANDATORY: 의무사항. 트랜잭션이 반드시 있어야 함. 기존 Tx 가 없으면 IllegalTransactionStateException 예외 발생, 기존에 있으면 기존 Tx 에 참여
 *     6. NEVER: 트랜잭션 사용 X. 기존 Tx 이 있으면 IllegalTransactionStateException 예외 발생. 기존 Tx 도 허용하지 않는 강한 부정의 의미. 없으면 없는대로 진행
 *     (MANDATORY <-> NEVER)
 *     7. NESTED: 기존 Tx 없으면 새로운 Tx 생성
 *                기존 Tx 있으면 중첩 트랜잭션을 만듦 => 중첩 Tx 은 외부 Tx 의 영향을 받지만, 중첩 Tx 은 외부에 영향을 주지 않음
 *                                              => 중첩 Tx 이 롤백되어도 외부 Tx 은 커밋 가능
 *                                              => 외부 Tx 이 롤백되면 중첩 Tx 도 함께 롤백
 *                                              (내부는 외부에 종속적, 외부는 내부에 종속성 x 로 이해하면 될듯)
 *                                              => JDBC savepoint 기능 사용 => DB 드라이버에서 해당 기능 지원하는지 확인 필요
 *                                              (JPA 에서는 사용 불가능, 실제로 잘 사용하지는 않음)
 *
 *     ** @Transactional 옵션인 isolation, timeout, readOnly 는 Tx 이 처음 시작될 때(새로운 Tx 생성)만 적용. 참여하는 경우는 적용 불가능
 *     (그럼 아마 이 옵션을 다르게 해줘야 하는 경우에 새로운 tx 사용하는 전파 옵션을 사용하지 않을까 하는 개인적인 생각)
 * </전파옵션>
 */
@Slf4j
@SpringBootTest
public class BasicTxTest2 {

    @Autowired
    PlatformTransactionManager txManager;

    @TestConfiguration
    static class Config {
        @Bean
        public PlatformTransactionManager transactionManager (DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }
    }

    /**
     * 외부 트랜잭션 시작
     * Creating new transaction with name [null]: PROPAGATION_REQUIRED,ISOLATION_DEFAULT        // 새 tx 생성
     * Acquired Connection [HikariProxyConnection@1589288513 wrapping conn0] for JDBC transaction   // 커넥션 획득
     * Switching JDBC Connection [HikariProxyConnection@1589288513 wrapping conn0] to manual commit // 물리 트랜잭션 (외부) 시작 (manual commit)
     * outer.isNewTransaction()=true        // 새로운 트랜잭션임을 확인
     * 내부 트랜잭션 시작
     * Suspending current transaction, creating new transaction with name [null]        // 다시 새로운 tx 생성하기 위해 기존의 tx 를 잠깐 미뤄넣음
     * Acquired Connection [HikariProxyConnection@721840156 wrapping conn1] for JDBC transaction        // 새 커넥션 획득
     * Switching JDBC Connection [HikariProxyConnection@721840156 wrapping conn1] to manual commit      // 물리 트랜잭션 (내부) 시작
     * inner.isNewTransaction()=true        // 내부 커넥션도 새로운 tx 임을 확인
     * 내부 트랜잭션 롤백
     * Initiating transaction rollback
     * Rolling back JDBC transaction on Connection [HikariProxyConnection@721840156 wrapping conn1]     // 내부 tx 롤백
     * Releasing JDBC Connection [HikariProxyConnection@721840156 wrapping conn1] after transaction     // 내부 tx 종료
     * Resuming suspended transaction after completion of inner transaction         // 내부 tx 가 끝나고, 미뤄놨던 외부 tx 재개
     *                                                                              // 미뤄놨다는 건 다른 뜻이 아니고 그냥 사용하지 않는 다는 뜻임
     * 외부 트랜잭션 커밋
     * Initiating transaction commit
     * Committing JDBC transaction on Connection [HikariProxyConnection@1589288513 wrapping conn0]
     * Releasing JDBC Connection [HikariProxyConnection@1589288513 wrapping conn0] after transaction
     */
    @Test
    void innerRollbackRequiresNew () {
        log.info("외부 트랜잭션 시작");
        TransactionStatus outer = txManager.getTransaction(new DefaultTransactionAttribute());
        log.info("outer.isNewTransaction()={}", outer.isNewTransaction());      // true

        innerLogic();

        log.info("외부 트랜잭션 커밋");
        txManager.commit(outer);    // 커밋
    }

    private void innerLogic() {
        log.info("내부 트랜잭션 시작");
        DefaultTransactionAttribute definition = new DefaultTransactionAttribute();
        definition.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);  // 기존 tx 무시하고, 신규 tx 만듦
        // default: 'PROPAGATION_REQUIRED' (기존 tx 에 참여)
        TransactionStatus inner = txManager.getTransaction(definition);
        log.info("inner.isNewTransaction()={}", inner.isNewTransaction());      // true

        log.info("내부 트랜잭션 롤백");
        txManager.rollback(inner);  // 롤백
    }
}
