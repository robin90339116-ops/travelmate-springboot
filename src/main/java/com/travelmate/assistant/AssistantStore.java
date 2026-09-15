package com.travelmate.assistant;

import com.travelmate.common.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor
public class AssistantStore {
    private final AssistantSessionRepository sessions;
    private final AssistantTraceRepository traces;
    @Transactional
    public void saveTurn(AssistantSession session, AssistantTrace trace) {
        // Optimistic version check prevents concurrent responses from overwriting context.
        sessions.saveAndFlush(session);
        traces.save(trace);
    }
    @Transactional
    public void delete(String id, Long uid) {
        var session = sessions.findByIdAndUserId(id, uid)
                .orElseThrow(() -> ApiException.notFound("会话不存在"));
        traces.deleteAll(traces.findBySessionIdAndUserId(id, uid));
        sessions.delete(session);
    }
}
