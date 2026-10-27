package com.springboot.backend.email;

import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

@Component
public class SmtpRecommendationEmailSender implements RecommendationEmailSender {

    private final JavaMailSender mailSender;
    private final RecommendationEmailSettings settings;

    public SmtpRecommendationEmailSender(JavaMailSender mailSender, RecommendationEmailSettings settings) {
        this.mailSender = mailSender;
        this.settings = settings;
    }

    @Override
    public void send(String recipient, String subject, String body) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(settings.fromAddress());
        message.setTo(recipient);
        message.setSubject(subject);
        message.setText(body);
        mailSender.send(message);
    }
}