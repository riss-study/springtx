package dev.riss.springtx.propagation.domain.log;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Slf4j
@Repository
@RequiredArgsConstructor
public class LogRepository {

    private final EntityManager em;

    @Transactional(propagation = Propagation.REQUIRES_NEW)      // 기존 Tx 가 있더라도 새로운 Tx 로 동작
    public void saveRequiresNew (Log logMessage) {
        log.info("Log 저장");
        em.persist(logMessage);

        if (logMessage.getMessage().contains("로그예외")) {
            log.info("log 저장 시 예외 발생");
            throw new RuntimeException("예외 발생");
        }
    }

    @Transactional
    public void save (Log logMessage) {
        log.info("Log 저장");
        em.persist(logMessage);

        if (logMessage.getMessage().contains("로그예외")) {
            log.info("log 저장 시 예외 발생");
            throw new RuntimeException("예외 발생");
        }
    }

    public void saveNotTx (Log logMessage) {
        log.info("Log 저장");
        em.persist(logMessage);

        if (logMessage.getMessage().contains("로그예외")) {
            log.info("log 저장 시 예외 발생");
            throw new RuntimeException("예외 발생");
        }
    }

    public Optional<Log> find (String message) {
        return em.createQuery("SELECT l FROM Log l WHERE l.message=:message", Log.class)
                .setParameter("message", message)
                .getResultList().stream().findAny();
    }

}
