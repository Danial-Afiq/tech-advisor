package com.springboot.backend.email;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSender;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SmtpRecommendationEmailSenderTest {

    @Test
    void sendsPlainTextAndHtmlAsMultipartAlternatives() throws Exception {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        when(mailSender.createMimeMessage()).thenReturn(message);
        RecommendationEmailSettings settings = new RecommendationEmailSettings(
                "STRONG_UPGRADE_CANDIDATE",
                "B",
                3,
                "http://localhost:5173/dashboard",
                "no-reply@techadvisor.local");

        new SmtpRecommendationEmailSender(mailSender, settings).send(
                "user@example.com",
                "Subject",
                "Plain-text fallback",
                "<html><body><h1>HTML version</h1></body></html>");

        verify(mailSender).send(message);
        message.saveChanges();
        ByteArrayOutputStream wireMessage = new ByteArrayOutputStream();
        message.writeTo(wireMessage);
        String source = wireMessage.toString(StandardCharsets.UTF_8);
        assertThat(source)
                .contains("multipart/alternative")
                .contains("Plain-text fallback")
                .contains("<h1>HTML version</h1>");
    }
}
