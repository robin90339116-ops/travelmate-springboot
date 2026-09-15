package com.travelmate.ai;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;
import java.time.Instant;
import java.util.*;

public interface GuideJobRepository extends JpaRepository<GuideJob,String> {
    List<GuideJob> findByUserId(Long userId);
    List<GuideJob> findByTeamIdOrderByCreatedAtAsc(Long teamId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select j from GuideJob j where j.id=:id")
    Optional<GuideJob> lockById(@Param("id") String id);
    @Query("select j.id from GuideJob j where (j.status='running' and (j.leaseUntil<:now or (j.leaseUntil is null and j.updatedAt<:stale))) or (j.status='queued' and j.updatedAt<:stale) order by j.updatedAt")
    List<String> recoveryIds(@Param("now") Instant now,@Param("stale") Instant stale,Pageable page);
}
