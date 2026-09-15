package com.travelmate.ai;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

@Entity @Getter @Setter
@Table(name="guide_outbox",indexes=@Index(name="idx_outbox_due",columnList="status,availableAt"))
public class GuideOutbox {
    @Id @Column(length=80) private String id;
    @Column(nullable=false,length=36) private String jobId;
    @ManyToOne(fetch=FetchType.LAZY)
    @JoinColumn(name="jobId",insertable=false,updatable=false,foreignKey=@ForeignKey(name="fk_outbox_job"))
    @org.hibernate.annotations.OnDelete(action=org.hibernate.annotations.OnDeleteAction.CASCADE)
    private GuideJob job;
    private int generation;
    @Column(nullable=false,length=16) private String status="pending";
    private int publishAttempts;
    private Instant availableAt=Instant.now();
    private Instant leaseUntil;
    @Column(length=36) private String leaseToken;
    @Column(length=200) private String lastError;
}
