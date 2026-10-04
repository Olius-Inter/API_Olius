package br.com.olius.olius_core_api.testfixture;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/** Entidade exclusiva do teste de flush; não faz parte do modelo OLIUS. */
@Entity
@Table(name = "flush_probe", schema = "test_support")
public class FlushProbe {
    @Id
    private UUID id;
    @Column(name = "probe_value")
    private String value;

    protected FlushProbe() {}

    public FlushProbe(UUID id, String value) {
        this.id = id;
        this.value = value;
    }
}
