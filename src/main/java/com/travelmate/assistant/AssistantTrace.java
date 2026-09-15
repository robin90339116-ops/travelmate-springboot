package com.travelmate.assistant;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;
@Entity @Getter @Setter
@Table(name="assistant_trace", indexes=@Index(name="idx_trace_owner", columnList="userId"))
public class AssistantTrace {
    @Id private String id;
    private Long userId;
    private String sessionId;
    private String model;
    private String promptVersion;
    private String status;
    @Column(length=256) private String failureReason;
    private long elapsedMs;
    @Lob private String stepsJson;
    private Instant createdAt = Instant.now();
}
