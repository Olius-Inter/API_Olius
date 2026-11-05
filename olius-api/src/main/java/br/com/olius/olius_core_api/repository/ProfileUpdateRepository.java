package br.com.olius.olius_core_api.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ProfileUpdateRepository {
    public record Phone(UUID id,String number) { }
    private final JdbcTemplate jdbc;
    public ProfileUpdateRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public boolean lockActiveUser(UUID id) {
        return !jdbc.query("SELECT id FROM public.users WHERE id=? AND status='ACTIVE' FOR UPDATE",
            (rs,row)->rs.getObject(1,UUID.class),id).isEmpty();
    }
    public void updateName(UUID id,String name) {
        jdbc.update("UPDATE public.users SET name=? WHERE id=? AND name IS DISTINCT FROM ?",name,id,name);
    }
    public List<Phone> phones(UUID user) {
        return jdbc.query("SELECT id,telephone FROM public.telephone WHERE user_id=?",
            (rs,row)->new Phone(rs.getObject("id",UUID.class),rs.getString("telephone")),user);
    }
    public void removePhone(UUID user,UUID id) { jdbc.update("DELETE FROM public.telephone WHERE id=? AND user_id=?",id,user); }
    public void addPhone(UUID user,String number) {
        jdbc.update("INSERT INTO public.telephone(id,user_id,telephone) VALUES (?,?,?)",UUID.randomUUID(),user,number);
    }
}
