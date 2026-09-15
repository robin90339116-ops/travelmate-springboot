package com.travelmate.ai;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;
import java.time.Instant;
import java.util.*;

public interface GuideOutboxRepository extends JpaRepository<GuideOutbox,String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from GuideOutbox e where e.id=:id")
    Optional<GuideOutbox> lockById(@Param("id") String id);
    @Query("select e.id from GuideOutbox e where (e.status='pending' and e.availableAt<=:now) or (e.status='sending' and e.leaseUntil<:now) order by e.availableAt")
    List<String> dueIds(@Param("now") Instant now,Pageable page);
    List<GuideOutbox> findByJobId(String jobId);
}
