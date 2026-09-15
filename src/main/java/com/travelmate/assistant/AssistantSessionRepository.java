package com.travelmate.assistant;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface AssistantSessionRepository extends JpaRepository<AssistantSession,String> {
    Optional<AssistantSession> findByIdAndUserId(String id, Long userId);
    List<AssistantSession> findByUserIdOrderByUpdatedAtDesc(Long userId);
}
