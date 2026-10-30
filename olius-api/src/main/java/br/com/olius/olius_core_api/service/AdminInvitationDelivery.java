package br.com.olius.olius_core_api.service;

/** Canal de entrega do convite após commit. Nunca implementar por logs. */
public interface AdminInvitationDelivery {
    void send(Message message);

    record Message(String name,String email,String token) {
        @Override public String toString() { return "AdminInvitationMessage[redacted]"; }
    }
}
