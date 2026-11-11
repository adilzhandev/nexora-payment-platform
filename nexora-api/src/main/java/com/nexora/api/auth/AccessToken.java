package com.nexora.api.auth;

import com.nexora.api.user.AppUser;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "access_tokens")
public class AccessToken {
    @Id
    private String tokenHash;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private AppUser user;
    private Instant expiresAt;

    protected AccessToken() {}

    public AccessToken(String tokenHash, AppUser user, Instant expiresAt) {
        this.tokenHash = tokenHash;
        this.user = user;
        this.expiresAt = expiresAt;
    }

    public AppUser getUser() { return user; }
    public Instant getExpiresAt() { return expiresAt; }
}
