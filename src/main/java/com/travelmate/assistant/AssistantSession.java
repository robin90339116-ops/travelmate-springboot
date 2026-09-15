package com.travelmate.assistant;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

@Entity @Getter @Setter
@Table(name="assistant_session", indexes=@Index(name="idx_assistant_owner", columnList="userId"))
public class AssistantSession {
    @Id private String id;
    @Version private long version;
    @Column(nullable=false) private Long userId;
    @Column(nullable=false, length=32) private String cityKey;
    private int durationMinutes;
    @Column(length=200) private String interests = "";
    @Column(length=80) private String companions = "";
    @Lob @Column(nullable=false) private String historyJson = "[]";
    @Lob @Column(nullable=false) private String routeJson = "[]";
    @Lob private String lastResultJson;
    private Instant updatedAt = Instant.now();
}
