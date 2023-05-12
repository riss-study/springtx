package dev.riss.springtx.order;

import lombok.extern.slf4j.Slf4j;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Rollback;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.*;

@Slf4j
@SpringBootTest
class OrderServiceTest {

    @Autowired OrderService orderService;
    @Autowired OrderRepository orderRepository;

    @Test
    void complete() throws NotEnoughMoneyException {

        // given
        Order order=new Order("정상");

        // when
        orderService.order(order);

        // then
        Order findOrder = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(findOrder.getPayStatus()).isEqualTo(Order.OrderStatus.완료);
    }

    @Test
    void runtimeException() {

        // given
        Order order=new Order("예외");

        // when
        assertThatThrownBy(() -> orderService.order(order))
                .isInstanceOf(RuntimeException.class);

        // then
        Optional<Order> orderOptional = orderRepository.findById(order.getId());
        assertThat(orderOptional.isEmpty()).isTrue();
    }

    @Test
    void bizException () {
        // given
        Order order=new Order("잔고부족");

        // when
        try {
            orderService.order(order);
        } catch (NotEnoughMoneyException e) {
            log.info("고객에게 잔고 부족을 알리고 별도의 계좌로 입금하도록 안내 (얘가 컨트롤러라는 가정 하에)");
        }

        // then
        Order findOrder = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(findOrder.getPayStatus()).isEqualTo(Order.OrderStatus.대기);
    }
}