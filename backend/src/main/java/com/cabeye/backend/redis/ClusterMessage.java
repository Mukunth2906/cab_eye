package com.cabeye.backend.redis;

import com.cabeye.backend.model.RideEvent;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Encapsulates a cross-instance ride event delivered via Redis Pub/Sub.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ClusterMessage {

    private String senderInstanceId;
    private String rideId;
    private String role;
    private String excludeSessionId;
    private RideEvent event;

    public ClusterMessage() {}

    public ClusterMessage(String senderInstanceId, String rideId, String role, String excludeSessionId, RideEvent event) {
        this.senderInstanceId = senderInstanceId;
        this.rideId = rideId;
        this.role = role;
        this.excludeSessionId = excludeSessionId;
        this.event = event;
    }

    public String getSenderInstanceId() {
        return senderInstanceId;
    }

    public void setSenderInstanceId(String senderInstanceId) {
        this.senderInstanceId = senderInstanceId;
    }

    public String getRideId() {
        return rideId;
    }

    public void setRideId(String rideId) {
        this.rideId = rideId;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getExcludeSessionId() {
        return excludeSessionId;
    }

    public void setExcludeSessionId(String excludeSessionId) {
        this.excludeSessionId = excludeSessionId;
    }

    public RideEvent getEvent() {
        return event;
    }

    public void setEvent(RideEvent event) {
        this.event = event;
    }
}
