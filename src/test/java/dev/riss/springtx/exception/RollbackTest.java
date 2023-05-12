package dev.riss.springtx.exception;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.*;

/**
 * Tx 예외 발생 시 커밋 or 롤백
 * - checked (보통 비즈니스 의미가 있을 때 사용): commit
 * - Runtime(unChecked, 북구 불가능한 예외): rollback
 *
 * @Transactional(rollbackFor=MyException.class): 명시해놓은 클래스 예외 발생 시, 롤백 시킴
 */
@SpringBootTest
public class RollbackTest {

    @Autowired RollbackService rollbackService;

    @Test
    void runtimeException () {
        // 롤백된 것을 로그 확인 가능
        assertThatThrownBy(() -> rollbackService.runtimeException())
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void checkedException () {
        // 커밋된 것을 로그 확인 가능
        assertThatThrownBy(() -> rollbackService.checkedException())
                .isInstanceOf(MyException.class);
    }

    @Test
    void rollbackFor () {
        // 롤백된 것을 로그 확인 가능
        assertThatThrownBy(() -> rollbackService.rollbackFor())
                .isInstanceOf(MyException.class);
    }

    @TestConfiguration
    static class RollbackTestConfig {

        @Bean
        RollbackService rollbackService () {
            return new RollbackService();
        }
    }

    @Slf4j
    static class RollbackService {

        // Runtime exception: rollback (default)
        @Transactional
        public void runtimeException () {
            log.info("call runtimeException");
            throw new RuntimeException();
        }

        // Checked exception: commit (default)
        @Transactional
        public void checkedException () throws Exception {
            log.info("call checkedException");
            throw new MyException();
        }

        // Checked exception -> use rollbackFor: rollback
        @Transactional(rollbackFor = MyException.class)
        public void rollbackFor () throws Exception {
            log.trace("call checkedException rollbackFor");
            throw new MyException();
        }


    }

    static class MyException extends Exception {

    }
}
