package com.example.booking.user;

import jakarta.persistence.*;
import java.time.Instant;

/** Named AppUser because "user" is a reserved word in PostgreSQL; the table is "users". */
@Entity
@Table(name = "users")
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String email;

    @Column(name = "password_hash")
    private String passwordHash;

    private String name;

    @Enumerated(EnumType.STRING)
    private Role role;

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();

    protected AppUser() {}

    public AppUser(String email, String passwordHash, String name, Role role) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.name = name;
        this.role = role;
    }

    public Long getId() { return id; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public String getName() { return name; }
    public Role getRole() { return role; }
}
