package dev.riss.springtx.propagation;

import dev.riss.springtx.propagation.domain.log.LogRepository;
import dev.riss.springtx.propagation.domain.member.MemberRepository;
import lombok.extern.slf4j.Slf4j;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.UnexpectedRollbackException;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 주의!
 *   REQUIRES_NEW 를 사용하면, 하나의 HTTP 요청에 DB 커넥션을 2개 사용됨
 *   => 기존의 커넥션은 잠깐 안쓰는 것이지 반환하는 게 아니므로, 실제 데이터베이스 커넥션을 2개 들고 있음
 *   ==> 성능이 중요한 곳에서는 주의해서 사용해야 함 (REQUIRES_NEW 안쓰고 해결할 수 있으면 BEST)
 *
 *   ex. MemberFacade
 *       --> 물리 Tx 1(conn1) [MemberService(논리 Tx A) --> MemberRepository(논리 Tx C)]
 *       --> 물리 Tx 2(conn2) [LogRepository(논리 Tx C)]
 *   => 앞단에 Facade 처럼 클래스 하나 만들고 각각 Tx 를 묶지 않고 분리해서
 *      Facade 에서 memberService 호출 -> memberRepository 호출 로직 끝나면 Tx 종료하고
 *      Facade 에서 LogRepository 를 이후에 호출하여 Tx 진행 (MemberService 에서 호출 안되게 바깥에서 만들어서 따로 순차적으로 호출)
 *      이때 HTTP 요청 하나에 동시에 2개의 커넥션을 사용하지 않음
 *      구조상 REQUIRES_NEW 가 깔끔한 경우도 있으니, 상황에 맞게 사용하자
 */
@Slf4j
@SpringBootTest
public class MemberServiceRecoverExceptionTxTest {

    @Autowired
    MemberService memberService;
    @Autowired
    MemberRepository memberRepository;
    @Autowired
    LogRepository logRepository;

    /**
     * memberService        @Transactional: ON REQUIRED
     * memberRepository     @Transactional: ON REQUIRED
     * logRepository        @Transactional: ON REQUIRED Exception
     *
     * (논리 Tx B 커밋 단계까지 MemberServiceSingTxTest.outerTxOn_fail() 과 같음)
     * 1.LogRepository 가 예외 발생, Tx AOP 가 해당 예외 받음
     *   => 신규 tx 아니므로 물리 롤백하지 않고 TxSyncManager 에 rollbackOnly 표시
     * 2. 이후 Tx AOP 가 해당 예외 밖으로 던짐
     * 3. MemberService 가 런타임 예외를 받게 됨. 해당 로직에서는 런타임 예외를 처리하여 복구했으므로 정상적으로 리턴함
     * 4. 정상 흐름이 됐으므로 MemberService 의 Tx AOP 는 커밋 호출 (Tx Manager 에게 커밋 요청)
     *   => 신규 Tx 이므로, 실제 물리 Tx 를 커밋해야 함. 이 시점에 rollbackOnly 설정 체크
     * 5. rollbackOnly 가 체크돼있으므로 Tx Manager 는 물리 Tx 를 롤백
     * 6. Tx Manager 는 UnexpectedRollbackException 예외를 던짐 (커밋을 요청한 MemberService 의 Tx AOP 에게)
     * 7. 이를 전달받은 MemberService 의 Tx AOP 는 전달받은 UnexpectedRollbackException 을 Client 에게 던짐
     *
     * ==> 둘다 롤백됐으므로 데이터 정합성이 맞으며, Client 는 커밋을 기대했지만 롤백이 됐으므로 UnexpectedRollbackException 을 받고
     *     기대하지 않은 롤백 상황을 알 수 있음
     */
    @Test
    void recoverException_fail() {
        // given
        String username="로그예외_recoverException_fail";

        // when
        Assertions.assertThatThrownBy(() -> memberService.joinV2(username))
                .isInstanceOf(UnexpectedRollbackException.class);

        // then: 예외를 잡았음에도 불구하고 이미 논리 tx 안에서 예외를 던지는 시점에 rollbackOnly 설정이 대있으므로, 모든 데이터가 롤백
        assertTrue(memberRepository.find(username).isEmpty());
        assertTrue(logRepository.find(username).isEmpty());
    }

    /**
     * memberService        @Transactional: ON REQUIRED     --> conn1
     * memberRepository     @Transactional: ON REQUIRED     --> conn1
     * logRepository        @Transactional: ON REQUIRES_NEW (Tx 분리) Exception    --> conn2
     *
     * MemberRepository 는 REQUIRED 옵션 사용 => 기존 Tx 에 참여
     * => 논리 Tx 존재
     * LogRepository 는 REQUIRES_NEW 옵션 사용 => 항상 새로운 Tx 만듦 ==> 해당 Tx 안에는 DB 커넥션도 별도로 사용하게 됨 (새로운 물리 Tx)
     * => Tx 와 connection 이 1대1이기 때문에, 실제로는 물리 Tx 만 있고 논리 Tx 개념이 없음
     *
     * 1. LogRepository 에서 예외 발생. 예외를 던지면 LogRepository 의 Tx AOP 가 해당 예외 받음
     * 2. REQUIRES_NEW 를 사용한 신규 tx 이므로 물리 tx 를 롤백. 물리 tx 를 롤백했으므로 rollbackOnly 표시하지 않음.
     *   => 여기서 REQUIRES_NEW 를 사용한 물리 Tx 은 롤백되고 완전히 끝남
     * 3. 이후 Tx AOP 는 전달받은 예외를 밖으로 던짐
     * 4. 예외가 MemberService 에 던져지고, MemberService 는 해당 예외 복구 -> 정상 리턴
     * 5. 정상 흐름이 되었으므로 MemberService 의 Tx AOP 는 커밋 호출 -> Tx Manager 에게 커밋 요청
     * 6. 커밋 호출할 때 신규 Tx 이므로 실제 물리 Tx 커밋 해야 함. 이때 rollbackOnly 가 없으므로 물리 Tx 커밋
     * 7. 정상 흐름이 반환
     * 
     * => 회원 데이터는 저장, 로그 데이터만 롤백
     * (REQUIRES_NEW 가 아닌 다른 전파 옵션으로 만들 수도 있음)
     */
    @Test
    void recoverException_success() {
        // given
        String username="로그예외_recoverException_success";

        // when
        memberService.joinV3(username);

        // then: member 저장, log 만 롤백
        assertTrue(memberRepository.find(username).isPresent());
        assertTrue(logRepository.find(username).isEmpty());
    }
}
