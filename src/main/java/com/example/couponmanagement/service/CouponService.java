package com.example.couponmanagement.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class CouponService {
    public String acceptAny(String userId){

        return java.util.UUID.randomUUID().toString();
    }
}
