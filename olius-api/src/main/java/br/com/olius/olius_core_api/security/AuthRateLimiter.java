package br.com.olius.olius_core_api.security;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
/** Limite técnico por IP observado, com armazenamento limitado e janela de um minuto. */
public class AuthRateLimiter {
    private record Window(long started,int count) { }
    private final Map<String,Window> windows=new HashMap<>();
    private final int limit;
    private final int capacity;
    private final Clock clock;

    public AuthRateLimiter(int limit,int capacity,Clock clock) {
        if(limit<1 || capacity<1) throw new IllegalArgumentException("Limites técnicos devem ser positivos");
        this.limit=limit; this.capacity=capacity; this.clock=clock;
    }

    public synchronized boolean allow(String ip) {
        long now=clock.millis();
        Window old=windows.get(ip);
        if(old!=null && now-old.started()<60000) {
            if(old.count()>=limit) return false;
            windows.put(ip,new Window(old.started(),old.count()+1)); return true;
        }
        if(old==null && windows.size()>=capacity) {
            windows.entrySet().removeIf(entry -> now-entry.getValue().started()>=60000);
            if(windows.size()>=capacity) return false;
        }
        windows.put(ip,new Window(now,1)); return true;
    }
}
