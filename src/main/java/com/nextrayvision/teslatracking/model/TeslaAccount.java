package com.nextrayvision.teslatracking.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;

import java.time.Instant;

/** The single linked Tesla account (always id 1). */
@Entity
public class TeslaAccount {

    public static final long ID = 1L;

    @Id
    private Long id = ID;

    @Column(length = 8192)
    private String accessToken;

    @Column(length = 8192)
    private String refreshToken;

    private Instant expiresAt;

    /** Regional Fleet API URL reported by Tesla for this account. */
    private String fleetApiBaseUrl;

    private Instant linkedAt;

    public Long getId() { return id; }
    public String getAccessToken() { return accessToken; }
    public void setAccessToken(String accessToken) { this.accessToken = accessToken; }
    public String getRefreshToken() { return refreshToken; }
    public void setRefreshToken(String refreshToken) { this.refreshToken = refreshToken; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public String getFleetApiBaseUrl() { return fleetApiBaseUrl; }
    public void setFleetApiBaseUrl(String fleetApiBaseUrl) { this.fleetApiBaseUrl = fleetApiBaseUrl; }
    public Instant getLinkedAt() { return linkedAt; }
    public void setLinkedAt(Instant linkedAt) { this.linkedAt = linkedAt; }
}
