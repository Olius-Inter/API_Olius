package br.com.olius.olius_core_api.service;

import java.time.Instant;

public interface PasswordResetDelivery {
    record Message(String email,String token,Instant expiresAt) {
        @Override public String toString() { return "PasswordResetMessage[redacted]"; }
    }
    void send(Message message);
}
