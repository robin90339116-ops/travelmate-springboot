package com.travelmate.ai;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;
@Entity @Getter @Setter
@Table(name="guide_job", indexes={@Index(name="idx_job_owner",columnList="userId"), @Index(name="idx_job_recovery",columnList="status,leaseUntil"), @Index(name="idx_job_delivery",columnList="status,updatedAt")})
public class GuideJob {
 @Id private String id;
 @Column(nullable=false) private Long userId;
 private String spotId;
 private String style;
 private Long teamId;
 @Column(length=2000) private String question;
 @Column(length=4000) private String routeContext;
 private String status="queued";
 @Column(length=64) private String requestHash;
 @Column(nullable=false,columnDefinition="integer default 0") private int generation;
 @Column(nullable=false,columnDefinition="integer default 0") private int attempts;
 private Instant leaseUntil;
 @Column(length=36) private String executionToken;
 @Column(length=32000) private String content;
 @Column(length=500) private String error;
 private Instant createdAt=Instant.now();
 private Instant updatedAt=Instant.now();
}
