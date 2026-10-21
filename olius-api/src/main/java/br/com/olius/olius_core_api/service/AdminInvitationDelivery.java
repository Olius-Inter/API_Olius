package br.com.olius.olius_core_api.service;

/** A integração real de e-mail será instalada na segunda sprint. Não implementar por logs. */
public interface AdminInvitationDelivery {
    void send(Message message);

    record Message(String name,String email,String token) {
        @Override public String toString() { return "AdminInvitationMessage[redacted]"; }
    }
}
