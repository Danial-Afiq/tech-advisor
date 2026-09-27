package com.springboot.backend.service;

import com.springboot.backend.dto.AdminLoginRequest;
import com.springboot.backend.dto.LoginResponse;

import org.springframework.security.authentication.BadCredentialsException;
import com.springboot.backend.model.User;
import com.springboot.backend.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Locale;

@Service
public class AdminAuthenticationService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AdminAuthenticationService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService) {

        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    public LoginResponse login(
            AdminLoginRequest request) {

        String email = request
                .getEmail()
                .trim()
                .toLowerCase(Locale.ROOT);

        User admin = userRepository
                .findByEmailIgnoreCase(email)
                .filter(user ->
                        "ADMIN".equals(user.getRole()))
                .orElseThrow(() ->
                        new BadCredentialsException(
                                "Invalid email or password"
                        )
                );

        if (!passwordEncoder.matches(
                request.getPassword(),
                admin.getPasswordHash())) {

            throw new BadCredentialsException(
                "Invalid email or password"
            );
        }

        String token = jwtService.generateToken(admin);

        return new LoginResponse(
                token,
                jwtService.getExpirationSeconds()
        );
    }
}