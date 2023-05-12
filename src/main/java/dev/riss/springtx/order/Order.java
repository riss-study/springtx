package dev.riss.springtx.order;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "orders")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Getter
public class Order {

    @Id @GeneratedValue
    private Long id;

    private String username;    // 정상, 예외, 잔고부족

    @Enumerated(EnumType.STRING)
    private OrderStatus payStatus;   // 대기, 완료

    public Order(String username) {
        this.username = username;
    }

    public void update (OrderStatus payStatus) {
        this.payStatus=payStatus;
    }
    
    static enum OrderStatus {
        대기, 완료
    }

}
