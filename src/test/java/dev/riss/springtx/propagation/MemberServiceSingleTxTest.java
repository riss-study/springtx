package dev.riss.springtx.propagation;

import dev.riss.springtx.propagation.domain.log.LogRepository;
import dev.riss.springtx.propagation.domain.member.MemberRepository;
import lombok.extern.slf4j.Slf4j;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <트랜잭션 적용 범위에 따른 충돌 상황>
 * Client A: 자신의 메서드에 MemberService 로부터 MemberRepository, LogRepository 를 모두 하나의 트랜잭션으로 묶고 싶음
 * Client B: 자신의 메서드에 MemberRepository 만 호출하고 여기에만 Tx 을 사용하고 싶음
 * Client C: 자신의 메서드에 LogRepository 만 호출하고 여기에만 Tx 을 사용하고 싶음
 * Client Z 는 OrderService 에서 Tx 를 시작하고 클라이언트 A의 MemberService 로직을 호출하고 싶음
 *
 * A 입장에서는 MemberRepository, LogRepository 메서드에 @Transactional 을 모두 빼고 서비스 로직에만 해당 애노테이션 추가하면 됨
 * (트랜잭션 전파 개념 없다는 가정하에)
 * B, C 입장에서는 각각 Repository 에 @Transactional 을 넣어야 하는 상황
 * ... 그럼 메서드를 각각 만들어야 하나..? 의 문제 발생
 * Z 입장에서도 복잡한 계층에서 트랜잭션 시작 관련 문제 발생
 * ==> 트랜잭션 전파(propagation)를 이용
 */
@Slf4j
@SpringBootTest
public class MemberServiceSingleTxTest {

    @Autowired
    MemberService memberService;
    @Autowired
    MemberRepository memberRepository;
    @Autowired
    LogRepository logRepository;

    /**
     * memberService        @Transactional: ON
     * memberRepository     @Transactional: OFF
     * logRepository        @Transactional: OFF
     *
     * memberService 시작 ~ 종료할 때까지 모든 로직(MemberRepository, LogRepository 로직 모두)을 하나의 tx 으로 묶임
     * (같은 쓰레드 사용(쓰레드로컬에 이용) -> Tx Sync Manager 는 같은 커넥션 반환)
     * (memberRepository, logRepository 둘다 같은 tx 사용하므로 각각은 트랜잭션 AOP 적용 X => 순수한 자바 코드)
     *
     * ** 실제 insert 로그는 JPA 최적화 기능으로 인한 flush 시점이 transaction 끝나고 이루어지기 때문에 맨 뒤에 찍힘
     */
    @Test
    @Transactional
    void singleTx() {   // xxxNotTx 메서드는 이 테스트 때만 사용
        // given
        String username="singleTx";

        // when
        memberService.joinV1NotTx(username);

        // then
        assertTrue(memberRepository.find(username).isPresent());
        assertTrue(logRepository.find(username).isPresent());
    }

    /**
     * memberService        @Transactional: ON REQUIRED
     * memberRepository     @Transactional: ON REQUIRED
     * logRepository        @Transactional: ON REQUIRED
     *
     * 1. Client A 가 MemberService 를 호출 -> Tx AOP 호출 ==> 신규 커넥션 conn1 생성, 물리 tx 도 시작
     * 2. memberRepository 호출 -> Tx AOP 호출 => 이미 Tx 있으므로 기존 tx 에 참여
     * 3. memberRepository 호출 끝나고 정상 응답하면 Tx AOP 호출 => Tx AOP 는 정상 응답이므로 Tx Manager 에게 커밋 요청
     *   => 이 경우 신규 tx 이 아니므로 실제 커밋 호출하지 않음
     * 4. LogRepository 호출 -> Tx AOP 호출 => 이미 Tx 있으므로 기존 Tx 에 참여
     * 5. LogRepository 로직 호출 끝나고 정상 응답하면 Tx AOP 호출 => Tx AOP 는 정상 응답이므로 Tx Manager 에게 커밋 요청
     *   => 신규 Tx 이 아니므로 물리 커밋 호출 X
     * 6. MemberService 로직 호출이 끝나고 정상 응답하면 Transaction AOP 호출
     *   => Tx AOP 는 정상 응답이므로 Tx Manager 에게 커밋 요청
     *   => 신규 Tx 이므로 실제 물리 커밋 호출 O
     */
    @Test
    void outerTxOn_success() {
        // given
        String username="outerTxOn_success";

        // when
        memberService.joinV1Tx(username);

        // then
        assertTrue(memberRepository.find(username).isPresent());
        assertTrue(logRepository.find(username).isPresent());
    }

    /**
     * memberService        @Transactional: ON REQUIRED
     * memberRepository     @Transactional: ON REQUIRED
     * logRepository        @Transactional: ON REQUIRED Exception
     *
     * 1. Client A 가 MemberService 를 호출 -> Tx AOP 호출 ==> 신규 커넥션 conn1 생성, 물리 tx 도 시작
     * 2. memberRepository 호출 -> Tx AOP 호출 => 이미 Tx 있으므로 기존 tx 에 참여
     * 3. memberRepository 호출 끝나고 정상 응답하면 Tx AOP 호출 => Tx AOP 는 정상 응답이므로 Tx Manager 에게 커밋 요청
     *   => 이 경우 신규 tx 이 아니므로 실제 커밋 호출하지 않음
     * 4. LogRepository 호출 -> Tx AOP 호출 => 이미 Tx 있으므로 기존 Tx 에 참여
     * 5. LogRepository 로직에서 런타임 예외 발생 => 예외를 던지고 Tx AOP 는 해당 예외를 받음
     * 6. Tx AOP 는 런타임 예외가 발생했으므로 Tx Manager 에게 롤백 요청
     *   => 신규 Tx 이 아니므로 물리 롤백 호출하지 않고 Tx Sync Manager 에 'rollbackOnly' 설정
     *   (로그에 Participating transaction failed - marking existing transaction as rollback-only 가 찍힌걸 볼 수 있음)
     *   => LogRepository 가 예외를 던졌기 때문에, Tx AOP 도 해당 예외를 그대로 밖으로 던짐
     * 7. MemberService 에서도 런타임 예외를 받게 됨. 해당 로직에서는 런타임 예외를 처리하지 않고 밖으로 던짐
     *   => Tx AOP 는 런타임 예외가 발생 -> Tx manager 에게 롤백 요청. 신규 트랜잭션이므로 물리 롤백 호출
     *   (rollback-only 가 찍혀 있으나 이 경우 런타임 예외를 받아 롤백을 요청하기 때문에 해당 설정 고려하지 않음)
     *   => MemberService 가 예외를 던졌기 때문에, Tx AOP 도 밖으로 해당 예외 던짐
     * 8. Client A 는 LogRepository 부터 넘어온 이 런타임 예외를 받음
     * 
     * ==> 둘다 롤백됐으므로 데이터 정합성이 맞음
     */
    @Test
    void outerTxOn_fail() {
        // given
        String username="로그예외_outerTxOn_fail";

        // when
        Assertions.assertThatThrownBy(() -> memberService.joinV1Tx(username))
                .isInstanceOf(RuntimeException.class);

        // then: 모든 데이터가 롤백
        assertTrue(memberRepository.find(username).isEmpty());
        assertTrue(logRepository.find(username).isEmpty());
    }

}
