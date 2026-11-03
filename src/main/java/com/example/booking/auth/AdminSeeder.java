package com.example.booking.auth;

import com.example.booking.user.AppUser;
import com.example.booking.user.Role;
import com.example.booking.user.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class AdminSeeder implements ApplicationRunner {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final String email;
    private final String password;

    public AdminSeeder(UserRepository users, PasswordEncoder passwordEncoder,
                       @Value("${app.admin.email}") String email,
                       @Value("${app.admin.password}") String password) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.email = email;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!users.existsByEmail(email)) {
            users.save(new AppUser(email, passwordEncoder.encode(password), "Admin", Role.ADMIN));
        }
    }
}
