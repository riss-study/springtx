package dev.riss.springtx.propagation;

import dev.riss.springtx.propagation.domain.log.LogRepository;
import dev.riss.springtx.propagation.domain.member.MemberRepository;
import lombok.extern.slf4j.Slf4j;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

@Slf4j
@SpringBootTest
class MemberServiceTest {

    @Autowired MemberService memberService;
    @Autowired
    MemberRepository memberRepository;
    @Autowired
    LogRepository logRepository;

    /**
     * @Transactional == @Transactional(propagation=Propagation.REQUIRED)
     * memberService        @Transactional: OFF
     * memberRepository     @Transactional: ON
     * logRepository        @Transactional: ON
     *
     * 1. MemberService 에서 MemberRepository 호출 MemberRepository 에는 @Transactional 이 있으므로 트랜잭션 AOP 가 작동
     *    => 이때의 Tx 를 Tx B 라고 가정
     *    1-1. 트랜잭션 매니저에 Tx 요청하면 dataSource 를 통해 커넥션 conn1 을 획득하고, 해당 커넥션을 수동 커밋모드로 변경 후 tx 시작
     *    1-2. 트랜잭션 동기화 매니저를 통해 트랜잭션을 시작한 커넥션 보관
     *    1-3. 트랜잭션 매니저의 호출 결과로 status 를 반환. 이때 신규 트랜잭션 여부 true
     * 2. MemberRepository 는 JPA 를 통해 회원 저장 -> JPA 는 tx 이 시작한 conn1 을 사용하여 회원 저장 (em.persist(member))
     * 3. MemberRepository 가 정상 응답을 반환했기에 트랜잭션 AOP 프록시는 tx manager 에 커밋 요청
     * 4. tx manager 는 conn1 을 통해 물리 Tx 커밋 (이 시점에 신규 Tx 여부, rollbackOnly 여부 모두 체크 후 커밋 진행)
     *    => MemberRepository 관련 모든 데이터는 정상 커밋 -> Tx B 도 완전히 종료
     *
     * => 이후 LogRepository 도 1~4 의 과정과 동일하게 트랜잭션(Tx C 라고 가정)을 시작하고 정상 커밋
     * => 결과적으로 둘다 커밋 -> Member, Log 안전하게 저장
     */
    @Test
    void outerTxOff_success() {
        // given
        String username="outerTxOff_success";

        // when
        memberService.joinV1(username);

        // then: 모든 데이터가 정상 저장된다.
        assertTrue(memberRepository.find(username).isPresent());
        assertTrue(logRepository.find(username).isPresent());
    }

    /**
     * memberService        @Transactional: OFF
     * memberRepository     @Transactional: ON
     * logRepository        @Transactional: ON RuntimeException
     *
     * MemberRepository 를 호출하는 Tx B 부분은 outerTxOff_success() 주석의 1~4 와 동일
     * 
     * LogRepository 응답 로직 순서 (참고: 트랜잭션 AOP 프록시도 결국 내부에서 트랜잭션 매니저를 사용)
     * 5. LogRepository 는 트랜잭션 C 와 관련된 conn2 를 사용
     * 6. '로그예외' 가 포함된 이름을 전달해서 LogRepository 에 런타임 예외 발생
     * 7. LogRepository 는 해당 예외를 밖으로 덤짐. 이 경우 트랜잭션 AOP 프록시가 예외를 받게 됨
     * 8. 런타임 예외가 발생해서 트랜잭션 AOP 프록시는 트랜잭션 매니저에 롤백을 호출
     * 9. 트랜잭션 매니저는 신규 트랜잭셤임을 확인하고 conn2 를 통한 실제 물리 롤백 호출
     * => 이 경우 회원은 저장되지만, 회원 이력 로그가 롤백되므로 데이터 정합성에 문제가 발생할 수 있음 => 둘을 하나의 트랜잭션으로 묶어서 처리해야함
     */
    @Test
    void outerTxOff_fail() {
        // given
        String username="로그예외_outerTxOff_fail";

        // when
        Assertions.assertThatThrownBy(() -> memberService.joinV1(username))
                .isInstanceOf(RuntimeException.class);

        // then: Member 는 정상 저장, Log 는 런타임예외가 터지고 롤백됐으므로 비어있어야함
        assertTrue(memberRepository.find(username).isPresent());
        assertTrue(logRepository.find(username).isEmpty());
    }

}