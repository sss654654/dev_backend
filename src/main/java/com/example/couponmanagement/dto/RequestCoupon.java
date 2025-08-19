package com.example.couponmanagement.dto;

public class RequestCoupon {
    private String userId;
    public RequestCoupon(){}
    public String getUserId(){
        return userId;
    }
    public void setUserId(String userId) { this.userId = userId; }

}
