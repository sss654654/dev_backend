package com.example.couponmanagement.entity;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(
        name = "user_coupons",
        uniqueConstraints = {
                // 사용자별 같은 쿠폰 중복 발급 방지
                @UniqueConstraint(name = "uk_user_coupon", columnNames = {"userId","couponId"})
        }
)
public class UserCoupon {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable=false)
    private Long userId;

    @Column(nullable=false)
    private Long couponId;

    @Column(nullable=false, length=64)
    private String code;      // 발급된 쿠폰 코드(표시/사용용)

    private LocalDateTime issuedAt;

    /** 선택: 요청 중복 방지용 */
    @Column(length=64)
    private String requestId;
}

