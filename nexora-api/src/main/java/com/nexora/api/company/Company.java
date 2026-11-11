package com.nexora.api.company;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "companies")
public class Company {
    @Id
    private UUID id;
    private String name;
    private String registrationNumber;
    private boolean active;
    @Version
    private long version;
    private Instant createdAt;

    protected Company() {}

    public Company(String name, String registrationNumber) {
        this.id = UUID.randomUUID();
        this.name = name;
        this.registrationNumber = registrationNumber;
        this.active = true;
        this.createdAt = Instant.now();
    }

    public void updateProfile(String name, String registrationNumber) {
        this.name = name;
        this.registrationNumber = registrationNumber;
    }

    public void setActive(boolean active) { this.active = active; }
    public UUID getId() { return id; }
    public String getName() { return name; }
    public String getRegistrationNumber() { return registrationNumber; }
    public boolean isActive() { return active; }
    public long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
}
