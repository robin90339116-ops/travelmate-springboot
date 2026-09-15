package com.travelmate.assistant;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface AssistantTraceRepository extends JpaRepository<AssistantTrace,String> {
    Optional<AssistantTrace> findByIdAndUserId(String id, Long userId);
    List<AssistantTrace> findByUserId(Long userId);
    List<AssistantTrace> findBySessionIdAndUserId(String sessionId, Long userId);
}
