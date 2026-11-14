package com.springboot.backend.email;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
public class SmtpRecommendationEmailSender implements RecommendationEmailSender {

    private final JavaMailSender mailSender;
    private final RecommendationEmailSettings settings;

    public SmtpRecommendationEmailSender(JavaMailSender mailSender, RecommendationEmailSettings settings) {
        this.mailSender = mailSender;
        this.settings = settings;
    }

    @Override
    public void send(String recipient, String subject, String plainText, String html) {
        MimeMessage message = mailSender.createMimeMessage();
        try {
            MimeMessageHelper helper = new MimeMessageHelper(
                    message,
                    true,
                    StandardCharsets.UTF_8.name());
            helper.setFrom(settings.fromAddress());
            helper.setTo(recipient);
            helper.setSubject(subject);
            helper.setText(plainText, html);
        } catch (MessagingException exception) {
            throw new MailPreparationException("Could not prepare recommendation email", exception);
        }
        mailSender.send(message);
    }
}
