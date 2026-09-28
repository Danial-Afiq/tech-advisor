package com.springboot.backend.config;

import com.springboot.backend.model.User;
import com.springboot.backend.repository.UserRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
public class AdminAccountInitializer
        implements ApplicationRunner {

    private final AdminSettings adminSettings;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public AdminAccountInitializer(
            AdminSettings adminSettings,
            UserRepository userRepository,
            PasswordEncoder passwordEncoder) {

        this.adminSettings = adminSettings;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(ApplicationArguments arguments) {

        String adminEmail = adminSettings
                .email()
                .trim()
                .toLowerCase(Locale.ROOT);

        userRepository
                .findByEmailIgnoreCase(adminEmail)
                .ifPresentOrElse(
                        existingUser -> {
                            if (!"ADMIN".equals(
                                    existingUser.getRole())) {

                                throw new IllegalStateException(
                                        "Configured admin email "
                                                + "already belongs "
                                                + "to a non-admin user"
                                );
                            }
                        },
                        () -> createAdmin(adminEmail)
                );
    }

    private void createAdmin(String adminEmail) {

        String passwordHash = passwordEncoder.encode(
                adminSettings.password()
        );

        User admin = new User(
                adminEmail,
                passwordHash,
                "ADMIN"
        );

        userRepository.save(admin);
    }
}