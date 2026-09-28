package com.springboot.backend.config;

import com.springboot.backend.model.User;
import com.springboot.backend.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminAccountInitializerTest {

    @Test
    void createsMissingAdminWithNormalizedEmailAndEncodedPassword() {
        AdminSettings settings = new AdminSettings(
                "  ADMIN@Example.COM  ",
                "long-enough-password"
        );
        UserRepository repository = mock(UserRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);

        when(repository.findByEmailIgnoreCase(
                "admin@example.com"
        )).thenReturn(Optional.empty());
        when(encoder.encode(
                "long-enough-password"
        )).thenReturn("encoded-password");

        AdminAccountInitializer initializer =
                new AdminAccountInitializer(
                        settings,
                        repository,
                        encoder
                );

        initializer.run(null);

        ArgumentCaptor<User> userCaptor =
                ArgumentCaptor.forClass(User.class);
        verify(repository).save(userCaptor.capture());

        User savedAdmin = userCaptor.getValue();
        assertEquals(
                "admin@example.com",
                savedAdmin.getEmail()
        );
        assertEquals(
                "encoded-password",
                savedAdmin.getPasswordHash()
        );
        assertEquals(
                "ADMIN",
                savedAdmin.getRole()
        );
    }

    @Test
    void existingAdminIsLeftUnchanged() {
        AdminSettings settings = new AdminSettings(
                "admin@example.com",
                "long-enough-password"
        );
        UserRepository repository = mock(UserRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);

        User existingAdmin = new User(
                "admin@example.com",
                "existing-hash",
                "ADMIN"
        );

        when(repository.findByEmailIgnoreCase(
                "admin@example.com"
        )).thenReturn(Optional.of(existingAdmin));

        AdminAccountInitializer initializer =
                new AdminAccountInitializer(
                        settings,
                        repository,
                        encoder
                );

        initializer.run(null);

        verify(encoder, never()).encode(anyString());
        verify(repository, never()).save(any(User.class));
    }

    @Test
    void existingNonAdminWithConfiguredEmailFailsFast() {
        AdminSettings settings = new AdminSettings(
                "admin@example.com",
                "long-enough-password"
        );
        UserRepository repository = mock(UserRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);

        User existingUser = new User(
                "admin@example.com",
                "existing-hash",
                "USER"
        );

        when(repository.findByEmailIgnoreCase(
                "admin@example.com"
        )).thenReturn(Optional.of(existingUser));

        AdminAccountInitializer initializer =
                new AdminAccountInitializer(
                        settings,
                        repository,
                        encoder
                );

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> initializer.run(null)
        );

        assertEquals(
                "Configured admin email already belongs to a non-admin user",
                exception.getMessage()
        );
        verify(encoder, never()).encode(anyString());
        verify(repository, never()).save(any(User.class));
    }
}
