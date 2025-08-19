package com.example.couponmanagement.repository;

import com.example.couponmanagement.entity.Coupon;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CouponRepository extends JpaRepository<Coupon, Long> {

}