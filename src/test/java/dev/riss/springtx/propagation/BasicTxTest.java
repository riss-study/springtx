package dev.riss.springtx.propagation;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.interceptor.DefaultTransactionAttribute;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.*;

/**
 * (1) <<트랜잭션 전파 (외부 - 내부) 요청 흐름>>
 * <요청흐름>
 *   <외부 tx>
 * 1. 'txManager.getTransaction()' 호출하여 외부 트랜잭션 시작
 * 2. tx Manager 는 dataSource 를 통해(꺼내서) connection 생성
 * 3. 생성한 connection 을 수동 커밋 모드('setAutoCommit(false)') 로 설정 -> 물리 트랜잭션 시작
 * 4. tx manager 는 'TransactionSynchronizationManager' 에 connection 보관
 * 5. tx manager 는 tx 을 생성한 결과를 'TransactionStatus' 에 담아서 반환 -> 여기에 신규 트랜잭션의 여부가 담겨져 있음
 *    => 'isNewTransaction' 을 통해 신규 트랜잭션 여부 확인 가능. tx 를 처음 시작했으므로 신규 tx 임 ('true')
 * 6. 내부 Tx 호출 전까지의 외부 메서드의 로직('로직1')이 사용되고 커넥션이 필요한 경우, 트랜잭션 동기화 매니저를 통해 트랜잭션이 적용된 커넥션 획득해서 사용
 *   </외부 tx>
 *   <내부 tx>
 * 7. 'txManager.getTransaction()' 호출하여 내부 트랜잭션 시작
 * 8. 트랜잭션 매니저는 트랜잭션 동기화 매니저를 통해 기존 트랜잭션이 존재하는지 확인
 * 9. 기존 트랜잭션이 존재하므로 기존 트랜잭션에 참여. -> 사실 아무것도 하지 않는다는 뜻 (기존 tx 에 참여하겠다는 로그 하나 남김)
 *    => 이미 기존 tx 인 외부 tx 에서 물리 트랜잭션을 시작함. 그리고 물리 tx 이 시작된 커넥션을 txSyncManager 에 담아 두었음
 *    => 따라서 이미 물리 tx 가 진행 중이므로 그냥 두면 이후의 로직이 기존에 시작된 tx 을 자연스럽게 사용하게 되는 것.
 *    => 이후의 로직은 자연스레 트랜잭션 동기화 매니저에 보관된 기존 커넥션을 사용
 * 10. tx manager 는 tx 을 생성한 결과를 'TransactionStatus' 에 담아서 반환 -> 'isNewTransaction' 을 통해 신규 트랜잭션의 여부 확인 가능
 *    => 기존 tx 에 참여했으므로 신규 트랜잭션이 아님 ('false')
 * 11. 내부 메서드의 로직('로직2')이 사용되고 커넥션이 필요한 경우, tx sync manager 를 통해 외부 tx 이 보관한 커넥션을 획득하여 사용
 *   </내부 tx>
 * </요청흐름>
 *
 * ** <정리>
 *    - 트랜잭션 매니저에 커밋 호출이 항상 실제 커넥션에 물리 커밋이 발생하는 것이 아님
 *    - 신규 tx 인 경우에만 실제 커넥션을 사용해서 물리 커밋과 롤백 수행 (신규가 아니라면 물리 커넥션 사용 X)
 *    - 트랜잭션이 내부에서 추가로 사용되는 경우 트랜잭션 매니저에 커밋하는 것이 항상 물리 커밋으로 이어지지 않음.
 *    => 이 경우 물리 트랜잭션 vs 논리 트랜잭션 으로 나뉘게 됨 (혹은 외부 vs 내부 트랜잭션)
 *    - 트랜잭션이 내부에서 추가로 사용 => 트랜잭션 매니저를 통해 논리 트랜잭션을 관리, 모든 논리 tx 이 커밋되면 물리 tx 이 커밋되는 것
 * 
 * (2) <<트랜잭션 전파 (외부 - 내부) 외부 커밋 - 내부 커밋 요청 (흐름 1)>>
 * <응답흐름>
 *   <내부 tx>
 * 12. 로직2가 끝나고 트랜잭션 매니저를 통해 내부 트랜잭션 커밋
 * 13. 트랜잭션 매니저는 커밋 시점에 신규 트랜잭션 여부에 따라 다르게 동작. (신규면 실제 커밋 or 롤백 호출)
 *    => 이 경우 신규 tx 이 아니기 때문에 실제 커밋 호출하지 않음
 *    ==> 실제 커넥션에 커밋이나 롤백 호출하면 물리 tx 이 끝나버림. 아직 tx 이 안끝났기 때문에 호출하면 안됨. (물리 tx 은 외부 tx 을 종료할 때까지 이어져야 함)
 *   </내부 tx>
 *   <외부 tx>
 * 14. 로직1이 끝나고 트랜잭션 매니저를 통해 외부 트랜잭션 커밋
 * 15. 트랜잭션 매니저는 커밋 시점에 외부 트랜잭션이 신규 트랜잭션임을 확인하고(동작 방식 13번 참조) DB 커넥션에 실제 커밋을 호출
 * 16. 트랜잭션 매니저에 커밋하는 것이 논리적인 커밋, 실제 커넥션에 커밋하는 것은 물리 커밋. 실제 데이터베이스에 커밋 반영되고 물리 tx 끝남
 *   </외부 tx>
 * </응답흐름>
 * 
 * (3) <<트랜잭션 전파 (외부 - 내부) 외부 롤백 - 내부 커밋 요청 (흐름 2)>>
 * <응답흐름>
 *  <내부 tx>
 *  12. 로직2가 끝나고 트랜잭션 매니저를 통해 내부 트랜잭션 커밋
 *  13. 트랜잭션 매니저는 커밋 시점에 신규 트랜잭션 여부에 따라 다르게 동작. (신규면 실제 커밋 or 롤백 호출)
 *     => 이 경우 신규 tx 이 아니기 때문에 실제 커밋 호출하지 않음
 *     ==> 실제 커넥션에 커밋이나 롤백 호출하면 물리 tx 이 끝나버림. 아직 tx 이 안끝났기 때문에 호출하면 안됨. (물리 tx 은 외부 tx 을 종료할 때까지 이어져야 함)
 *    </내부 tx>
 *    <외부 tx>
 *  14. 로직1이 끝나고 트랜잭션 매니저를 통해 외부 트랜잭션 롤백
 *  15. 트랜잭션 매니저는 롤백 시점에 외부 트랜잭션이 신규 트랜잭션임을 확인하고 DB 커넥션에 실제 롤백을 호출
 *  16. 트랜잭션 매니저에 롤백하는 것이 논리적인 롤백, 실제 커넥션에 롤백하는 것은 물리 롤백. 실제 데이터베이스에 롤백 반영되고 물리 tx 끝남
 *    </외부 tx>
 * </응답흐름>
 *
 * (4) <<트랜잭션 전파 (외부 - 내부) 내부 롤백 - 외부 커밋 요청 (흐름 3)>>
 * <응답흐름>
 *  <내부 tx>
 *  12. 로직2가 끝나고 트랜잭션 매니저를 통해 내부 트랜잭션 롤백 (로직2 에 문제가 있어서 롤백한다고 가정)
 *  13. 트랜잭션 매니저는 롤백 시점에 신규 트랜잭션 여부에 따라 다르게 동작. (신규면 실제 커밋 or 롤백 호출)
 *     => 이 경우 신규 tx 이 아니기 때문에 실제 롤백 호출하지 않음
 *     ==> 실제 커넥션에 커밋이나 롤백 호출하면 물리 tx 이 끝나버림. 아직 tx 이 안끝났기 때문에 호출하면 안됨. (물리 tx 은 외부 tx 을 종료할 때까지 이어져야 함)
 *  14. 내부 tx 은 물리 tx 을 롤백하지 않는 대신, 트랜잭션 동기화 매니저(TransactionSynchronizationManager) 에 'rollbackOnly=true' 라는 표시를 해둠
 *    </내부 tx>
 *    <외부 tx>
 *  15. 로직1이 끝나고 트랜잭션 매니저를 통해 외부 트랜잭션 커밋
 *  15. 트랜잭션 매니저는 커밋 시점에 외부 트랜잭션이 신규 트랜잭션임을 확인하고 DB 커넥션에 실제 커밋을 호출해야 함
 *      => 이때 먼저 tx sync manager 에 롤백 전용 ('rollbackOnly=true') 표시가 있는지 확인
 *      => 롤백 표시가 있으면 물리 tx 을 커밋하는 것이 아니라 롤백함
 *  16. 실제 데이터베이스에 롤백 반영되고 물리 tx 끝남
 *
 *  17. (중요!!) 트랜잭션 매니저에 커밋을 호출한 개발자 입장에선 분명 커밋을 기대했는데, 롤백 전용 표시로 인해 롤백이 진행됨
 *      => 조용히 넘거갈 수 있는 문제 X, 시스템 입장에서는 커밋을 호출했지만 롤백되었다는 것을 분명하게 알려줘야 함
 *      => ex. 고객은 주문이 성공했다고 생각했지만 실제로 롤백돼서 주문이 생성되지 않음
 *      ==> 스프링은 이 경우 'UnexpectedRollbackException' 런타임 예외를 던짐으로써 커밋을 시도했지만 기대하지 않은 롤백이 발생했다는 것을 명확히 알려줌
 *    </외부 tx>
 * </응답흐름>
 * ** <정리>
 *    - 논리 트랜잭션이 하나라도 롤백되면 물리 트랜잭션은 롤백
 *    - 내부 논리 트랜잭션이 롤백되면 롤백 전용 마크를 표시
 *    - 외부 트랜잭션을 커밋할 때 롤백 전용 마크를 확인. 롤백 전용 마크가 표시돼있으면 물리 트랜잭션을 롤백하고
 *      'UnexpectedRollbackException' 런타임 예외를 던짐
 *      
 * *** 애플리케이션 개발에서 가장 중요한 기본 원칙 -> 모호함 제거
 *     => 개발은 명확해야 함.
 *     => 커밋 호출했는데 내부에서 롤백 발생한 경우 모호하게 되면 심각한 문제 발생
 *     => 이렇게 기대한 결과가 다른 경우, 예외를 발생시켜서 명확하게 문제를 알려주는 것이 좋은 설계
 */
@Slf4j
@SpringBootTest
public class BasicTxTest {

    @Autowired
    PlatformTransactionManager txManager;       // 직접 등록한 트랜잭션매니저 주입받음

    @TestConfiguration
    static class Config {
        @Bean
        public PlatformTransactionManager transactionManager (DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);    // 스프링부트가 자동 등록해주지만 그거 말고 직접 등록해서 사용
        }
    }

    @Test
    void commit () {
        log.info("트랜잭션 시작");
        TransactionStatus status = txManager.getTransaction(new DefaultTransactionAttribute());

        log.info("트랜잭션 커밋");
        txManager.commit(status);
        log.info("트랜잭션 커밋 완료");
    }

    @Test
    void rollback () {
        log.info("트랜잭션 시작");
        TransactionStatus status = txManager.getTransaction(new DefaultTransactionAttribute());

        log.info("트랜잭션 롤백");
        txManager.rollback(status);
        log.info("트랜잭션 롤백 완료");
    }

    @Test
    void doubleCommit () {
        log.info("트랜잭션1 시작");
        TransactionStatus status1 = txManager.getTransaction(new DefaultTransactionAttribute());
        log.info("트랜잭션1 커밋");
        txManager.commit(status1);

        log.info("트랜잭션2 시작");
        TransactionStatus status2 = txManager.getTransaction(new DefaultTransactionAttribute());
        log.info("트랜잭션2 커밋");
        txManager.commit(status2);
    }

    @Test
    void doubleCommitRollback () {
        log.info("트랜잭션1 시작");
        TransactionStatus status1 = txManager.getTransaction(new DefaultTransactionAttribute());
        log.info("트랜잭션1 커밋");
        txManager.commit(status1);

        log.info("트랜잭션2 시작");
        TransactionStatus status2 = txManager.getTransaction(new DefaultTransactionAttribute());
        log.info("트랜잭션2 롤백");
        txManager.rollback(status2);
    }

    /**
     * 외부 트랜잭션 시작
     * Creating new transaction with name [null]: PROPAGATION_REQUIRED,ISOLATION_DEFAULT
     * Acquired Connection [HikariProxyConnection@825281081 wrapping conn0] for JDBC transaction
     * Switching JDBC Connection [HikariProxyConnection@825281081 wrapping conn0] to manual commit  //manual commit => setAutoCommit=false ==> 실제 Tx 시작
     * outer.isNewTransaction()=true
     * 내부 트랜잭션 시작           // 논리 트랜잭션
     * Participating in existing transaction        // 기존의 Tx 에 참여
     * inner.isNewTransaction()=false
     * 내부 트랜잭션 커밋           // 실제 물리 트랜잭션 커밋하지 않는 것을 볼 수 잇음
     * 외부 트랜잭션 커밋
     * Initiating transaction commit    // 이때 진짜 Tx commit
     * Committing JDBC transaction on Connection [HikariProxyConnection@825281081 wrapping conn0]
     * Releasing JDBC Connection [HikariProxyConnection@825281081 wrapping conn0] after transaction
     * 
     * => 여러 Tx 이 함께 사용되는 경우, 처음 Tx 를 시작한 외부 Tx 이 실제 물리 Tx 를 관리하도록 함 => 중복 커밋 문제 해결
     */
    @Test
    void innerCommit () {
        log.info("외부 트랜잭션 시작");
        TransactionStatus outer = txManager.getTransaction(new DefaultTransactionAttribute());
        log.info("outer.isNewTransaction()={}", outer.isNewTransaction());  // 처음 수행되는 tx 인지??

        innerTxLogic();

        log.info("외부 트랜잭션 커밋");
        txManager.commit(outer);        // 실제 커밋
    }

    private void innerTxLogic() {
        log.info("내부 트랜잭션 시작");     // 논리 트랜잭션
        TransactionStatus inner = txManager.getTransaction(new DefaultTransactionAttribute());
        log.info("inner.isNewTransaction()={}", inner.isNewTransaction());
        log.info("내부 트랜잭션 커밋");
        txManager.commit(inner);        // 물리 트랜잭션 입장에선 아무것도 안함
    }

    /**
     * 외부 tx 이 물리 tx 을 시작하고 롤백하는 것을 확인
     * 내부 tx 은 앞서 배운대로 직접 물리 tx 에 관여하지 않음
     * => 외부 트랜잭션에서 시작한 물리 트랜잭션의 범위가 내부 트랜잭션까지 사용됨. 이후 외부 tx 이 롤백되면서 전체 내용 모두 롤백
     */
    @Test
    void outerRollback () {
        log.info("외부 트랜잭션 시작");
        TransactionStatus outer = txManager.getTransaction(new DefaultTransactionAttribute());

        // inner logic start
        log.info("내부 트랜잭션 시작");
        TransactionStatus inner = txManager.getTransaction(new DefaultTransactionAttribute());
        log.info("내부 트랜잭션 커밋");
        txManager.commit(inner);        // 어차피 내부 커밋은 아무 일도 안함 (논리 커밋 o, 물리 커밋하지 않음)
        // inner logic end

        log.info("외부 트랜잭션 롤백");
        txManager.rollback(outer);
    }

    /**
     * 외부 트랜잭션 시작
     * Creating new transaction with name [null]: PROPAGATION_REQUIRED,ISOLATION_DEFAULT
     * Acquired Connection [HikariProxyConnection@1383055428 wrapping conn0] for JDBC transaction
     * Switching JDBC Connection [HikariProxyConnection@1383055428 wrapping conn0] to manual commit     // 외부 tx 시작 (물리 tx)
     * 내부 트랜잭션 시작
     * Participating in existing transaction    // 내부 tx 시작 (논리 tx -> 기존 tx 에 참여)
     * 내부 트랜잭션 롤백
     * Participating transaction failed - marking existing transaction as rollback-only     // rollback-only 라는 것을 현재 참여 중인 tx 에 마킹해놨다고 찍힘
     *                                  ==> 내부 tx 을 롤백하면 실제 물리 tx 은 롤백하지 않음. 대신에 기존 tx 을 롤백 전용으로 표시
     * Setting JDBC transaction [HikariProxyConnection@1383055428 wrapping conn0] rollback-only     // JDBC transaction 에 rollback-only 를 세팅해놓음
     * 외부 트랜잭션 커밋
     * Global transaction is marked as rollback-only but transactional code requested commit        // 전체 트랜잭션은 rollback-only 로 돼있으나, 현재 tx 코드는 commit 을 요청했다고 함
     *                                  ==> 커밋 호출했지만, 전체 tx 이 롤백 전용으로 표시됨 => 따라서 물리 트랜잭션을 롤백함
     * Initiating transaction rollback              // 내부적으로 실제 롤백 시작함
     * Rolling back JDBC transaction on Connection [HikariProxyConnection@1383055428 wrapping conn0]
     * Releasing JDBC Connection [HikariProxyConnection@1383055428 wrapping conn0] after transaction
     *
     *
     * org.springframework.transaction.UnexpectedRollbackException: Transaction rolled back because it has been marked as rollback-only
     */
    @Test
    void innerRollback () {
        log.info("외부 트랜잭션 시작");
        TransactionStatus outer = txManager.getTransaction(new DefaultTransactionAttribute());

        // inner logic start
        log.info("내부 트랜잭션 시작");
        TransactionStatus inner = txManager.getTransaction(new DefaultTransactionAttribute());
        log.info("내부 트랜잭션 롤백");
        txManager.rollback(inner);      // rollback-only 표시 (mark)
        // inner logic end

        log.info("외부 트랜잭션 커밋");
        assertThatThrownBy(() -> txManager.commit(outer))
                .isInstanceOf(UnexpectedRollbackException.class);
    }
}
