package com.springboot.backend.service;

import com.springboot.backend.dto.RegisterRequest;
import com.springboot.backend.dto.UserResponse;
import com.springboot.backend.model.User;
import com.springboot.backend.repository.UserRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

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
}