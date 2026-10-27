package com.springboot.backend.email;

public interface RecommendationEmailSender {

    void send(String recipient, String subject, String body);
}