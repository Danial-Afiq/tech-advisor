package com.springboot.backend.service;

import com.springboot.backend.dto.RegisterRequest;
import com.springboot.backend.dto.UserResponse;
import com.springboot.backend.dto.LoginRequest;
import com.springboot.backend.dto.LoginResponse;
import com.springboot.backend.model.User;
import com.springboot.backend.repository.UserRepository;
import org.springframework.security.authentication.BadCredentialsException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import java.util.Optional;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtService jwtService;

    @InjectMocks
    private UserService userService;

    @Test
    void registerShouldCreateUserSuccessfully() {
        RegisterRequest request = new RegisterRequest();
        request.setEmail("faith@example.com");
        request.setPassword("password123");

        when(userRepository.existsByEmail("faith@example.com"))
                .thenReturn(false);

        when(passwordEncoder.encode("password123"))
                .thenReturn("hashedPassword");

        when(userRepository.save(any(User.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        UserResponse response = userService.register(request);

        assertNotNull(response);
        assertEquals("faith@example.com", response.getEmail());
        assertEquals("USER", response.getRole());

        verify(passwordEncoder).encode("password123");
        verify(userRepository).save(any(User.class));
    }

    @Test
    void registerShouldRejectDuplicateEmail() {
        RegisterRequest request = new RegisterRequest();
        request.setEmail("faith@example.com");
        request.setPassword("password123");

        when(userRepository.existsByEmail("faith@example.com"))
                .thenReturn(true);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> userService.register(request)
        );

        assertEquals(
                "Email is already registered",
                exception.getMessage()
        );

        verify(passwordEncoder, never()).encode(anyString());
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void registerShouldHashPasswordBeforeSaving() {
        RegisterRequest request = new RegisterRequest();
        request.setEmail("faith@example.com");
        request.setPassword("password123");

        when(userRepository.existsByEmail("faith@example.com"))
                .thenReturn(false);

        when(passwordEncoder.encode("password123"))
                .thenReturn("$2a$10$exampleHashedPassword");

        when(userRepository.save(any(User.class)))
                .thenAnswer(invocation -> {
                    User user = invocation.getArgument(0);

                    assertNotEquals(
                            "password123",
                            user.getPasswordHash()
                    );

                    assertEquals(
                            "$2a$10$exampleHashedPassword",
                            user.getPasswordHash()
                    );

                    return user;
                });

        userService.register(request);

        verify(userRepository).save(any(User.class));
    }

    @Test
    void loginShouldReturnTokenWhenCredentialsAreCorrect() {

        LoginRequest request = new LoginRequest();
        request.setEmail("test@example.com");
        request.setPassword("password123");

        User user = new User(
                "test@example.com",
                "stored-password-hash",
                "USER"
        );

        when(userRepository.findByEmail("test@example.com"))
                .thenReturn(Optional.of(user));

        when(passwordEncoder.matches(
                "password123",
                "stored-password-hash"
        )).thenReturn(true);

        when(jwtService.generateToken(user))
                .thenReturn("test-jwt-token");

        when(jwtService.getExpirationSeconds())
                .thenReturn(3600L);

        LoginResponse response = userService.login(request);

        assertEquals("test-jwt-token", response.getToken());
        assertEquals("Bearer", response.getTokenType());
        assertEquals(3600L, response.getExpiresIn());

        verify(jwtService).generateToken(user);
    }

    @Test
    void loginShouldRejectUnknownEmail() {

        LoginRequest request = new LoginRequest();
        request.setEmail("unknown@example.com");
        request.setPassword("password123");

        when(userRepository.findByEmail("unknown@example.com"))
                .thenReturn(Optional.empty());

        assertThrows(
                BadCredentialsException.class,
                () -> userService.login(request)
        );

        verify(jwtService, never()).generateToken(any());
    }

    @Test
    void loginShouldRejectIncorrectPassword() {

        LoginRequest request = new LoginRequest();
        request.setEmail("test@example.com");
        request.setPassword("wrongpassword");

        User user = new User(
                "test@example.com",
                "stored-password-hash",
                "USER"
        );

        when(userRepository.findByEmail("test@example.com"))
                .thenReturn(Optional.of(user));

        when(passwordEncoder.matches(
                "wrongpassword",
                "stored-password-hash"
        )).thenReturn(false);

        assertThrows(
                BadCredentialsException.class,
                () -> userService.login(request)
        );

        verify(jwtService, never()).generateToken(any());
    }
}