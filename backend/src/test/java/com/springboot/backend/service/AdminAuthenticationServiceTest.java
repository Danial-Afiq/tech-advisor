package com.springboot.backend.service;

import com.springboot.backend.dto.AdminLoginRequest;
import com.springboot.backend.dto.LoginResponse;
import org.springframework.security.authentication.BadCredentialsException;
import com.springboot.backend.model.User;
import com.springboot.backend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminAuthenticationServiceTest {

    private UserRepository userRepository;
    private PasswordEncoder passwordEncoder;
    private JwtService jwtService;
    private AdminAuthenticationService service;
    private User admin;

    @BeforeEach
    void setUp() {

        userRepository = mock(UserRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        jwtService = mock(JwtService.class);

        service = new AdminAuthenticationService(
                userRepository,
                passwordEncoder,
                jwtService
        );

        admin = new User(
                "admin@techadvisor.com",
                "encoded-password",
                "ADMIN"
        );
    }

    @Test
    void validAdminCredentialsReturnAdminToken() {

        AdminLoginRequest request =
                new AdminLoginRequest();

        request.setEmail(
                "admin@techadvisor.com"
        );

        request.setPassword(
                "secure-password-123"
        );

        when(userRepository.findByEmailIgnoreCase(
                "admin@techadvisor.com"
        )).thenReturn(Optional.of(admin));

        when(passwordEncoder.matches(
                "secure-password-123",
                "encoded-password"
        )).thenReturn(true);

        when(jwtService.generateToken(admin))
                .thenReturn("admin-jwt");

        when(jwtService.getExpirationSeconds())
                .thenReturn(3600L);

        LoginResponse response =
                service.login(request);

        assertEquals(
                "admin-jwt",
                response.getToken()
        );

        assertEquals(
                "Bearer",
                response.getTokenType()
        );

        assertEquals(
                3600L,
                response.getExpiresIn()
        );

        verify(jwtService)
                .generateToken(admin);
    }

    @Test
    void invalidAdminPasswordDoesNotReturnToken() {

        AdminLoginRequest request =
                new AdminLoginRequest();

        request.setEmail(
                "admin@techadvisor.com"
        );

        request.setPassword(
                "wrong-password"
        );

        when(userRepository.findByEmailIgnoreCase(
                "admin@techadvisor.com"
        )).thenReturn(Optional.of(admin));

        when(passwordEncoder.matches(
                "wrong-password",
                "encoded-password"
        )).thenReturn(false);

        assertThrows(
                BadCredentialsException.class,
                () -> service.login(request)
        );

        verify(jwtService, never())
                .generateToken(any(User.class));
    }
}