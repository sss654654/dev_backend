package com.example.couponmanagement.repository;

import com.example.couponmanagement.entity.UserCoupon;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserCouponRepository extends JpaRepository<UserCoupon,Long> {
    boolean existsByUserIdAndCouponId(Long userId, Long couponId);
    Optional<UserCoupon> findByRequestId(String requestId);
}
