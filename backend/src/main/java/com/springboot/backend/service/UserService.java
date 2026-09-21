package com.springboot.backend.service;

import com.springboot.backend.dto.LoginRequest;
import com.springboot.backend.dto.LoginResponse;
import com.springboot.backend.dto.RegisterRequest;
import com.springboot.backend.dto.UserResponse;
import com.springboot.backend.model.User;
import com.springboot.backend.repository.UserRepository;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import java.util.Locale;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public UserService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService) {

        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    public UserResponse register(RegisterRequest request) {
        String email = request.getEmail().trim().toLowerCase(Locale.ROOT);

        if (userRepository.existsByEmailIgnoreCase(email)) {
                throw new IllegalArgumentException("Email is already registered");
        }

        String hashedPassword = passwordEncoder.encode(request.getPassword());

        User user = new User(email, hashedPassword, "USER");
        User savedUser = userRepository.save(user);

        return new UserResponse(savedUser);
    }

    public LoginResponse login(LoginRequest request) {
        String email = request.getEmail().trim().toLowerCase(Locale.ROOT);

        User user = userRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() ->
                        new BadCredentialsException("Invalid email or password"));

        if (!passwordEncoder.matches(
                request.getPassword(),
                user.getPasswordHash())) {
                throw new BadCredentialsException("Invalid email or password");
        }

        String token = jwtService.generateToken(user);

        return new LoginResponse(
                token,
                jwtService.getExpirationSeconds()
        );
    }

    public UserResponse getProfile(String email) {

        User user = userRepository.findByEmail(email)
                .orElseThrow(() ->
                        new IllegalArgumentException("User not found"));

        return new UserResponse(user);
    }
}