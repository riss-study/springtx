package dev.riss.springtx.order;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;

    // JPA 는 Tx commit 시점에 Order 데이터를 DB 에 반영함
    @Transactional
    public void order (Order order) throws NotEnoughMoneyException {
        log.info("call order");
        orderRepository.save(order);

        log.info("====pay process start====");
        String username = order.getUsername();

        if (username.equals("예외")) {
            log.info("System exception occurred");
            throw new RuntimeException("시스템 예외");

        } else if (username.equals("잔고부족")) {
            log.info("Insufficient balance business exception occurred");
            order.update(Order.OrderStatus.대기);
            throw new NotEnoughMoneyException("잔고가 부족합니다.");    // 이 예외를 받았을 때 동작하게 하는 프로세스가 있다고 가정
        }
        // 정상 승인
        log.info("normal approval");
        order.update(Order.OrderStatus.완료);

        log.info("====pay process completed====");
    }

}
