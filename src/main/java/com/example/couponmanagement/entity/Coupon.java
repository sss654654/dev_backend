package com.example.couponmanagement.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;


import java.time.LocalDateTime;

@Entity
@Table(name = "coupon")
public class Coupon {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable=false, length=100)
    private String name;

    /** 발급 가능한 총 수량(무제한이면 null) */
    private Integer totalQuantity;

    /** 남은 수량(무제한이면 null) */
    private Integer remainingQuantity;

    private LocalDateTime startsAt;     // 발급 시작
    private LocalDateTime expiresAt;    // 발급 종료

    /** 낙관적 락 */
    @Version
    private Long version;

    // getter/setter ...
    public boolean isExpired(LocalDateTime now) {
        if (startsAt != null && now.isBefore(startsAt)) return true;
        if (expiresAt != null && now.isAfter(expiresAt)) return true;
        return false;
    }

    public void decreaseOne() {
        if (remainingQuantity == null) return; // 무제한
        if (remainingQuantity <= 0) throw new IllegalStateException("쿠폰 재고 부족");
        remainingQuantity -= 1;
    }
}