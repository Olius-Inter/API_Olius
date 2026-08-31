package br.com.olius.olius_core_api.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Tabela: certificate
 *
 * issued_at é NOT NULL + DEFAULT CURRENT_TIMESTAMP no banco, mas
 * representa um evento de negócio distinto de created_at (a emissão
 * pode, em tese, ocorrer depois da criação do registro — daí a CHECK
 * created_at <= issued_at). Por isso NÃO usamos @CreationTimestamp aqui:
 * usamos um valor padrão via @Builder.Default para evitar o problema de
 * o Hibernate enviar NULL explicitamente no INSERT (ele sempre envia
 * todas as colunas mapeadas, então o DEFAULT do Postgres só valeria se a
 * coluna fosse omitida do INSERT — o que o Hibernate não faz por
 * padrão). Caso a emissão real deva ser setada manualmente pelo
 * serviço (ex.: só após gerar o PDF), remova o valor padrão e garanta
 * que o service sempre preencha o campo antes do persist.
 */
@Entity
@Table(name = "certificate")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString(onlyExplicitlyIncluded = true)
public class Certificate {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    @EqualsAndHashCode.Include
    @ToString.Include
    private UUID id;

    @Column(name = "certificate_code", nullable = false, unique = true, length = 64)
    @ToString.Include
    private String certificateCode;

    @Column(name = "pdf_url", length = 500)
    private String pdfUrl;

    @Builder.Default
    @Column(name = "issued_at", nullable = false)
    private LocalDateTime issuedAt = LocalDateTime.now();

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "certificate_level_id", nullable = false)
    private CertificateLevel certificateLevel;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "establishment_id", nullable = false)
    private Establishment establishment;
}
