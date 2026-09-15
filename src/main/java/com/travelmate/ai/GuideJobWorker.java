package com.travelmate.ai;

import com.travelmate.common.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service @RequiredArgsConstructor
public class GuideJobWorker {
    private final GuideJobStore store;
    private final AiService ai;
    public boolean run(AiDtos.GuideJobMessage message) {
        var execution=store.claim(message.jobId(),message.generation()==null?0:message.generation());
        if(execution==null)return false;
        String content;
        try {
            content=execution.question()==null
                    ?ai.explanation(new AiDtos.ExplanationRequest(execution.spotId(),execution.style(),execution.routeContext())).content()
                    :ai.chat(new AiDtos.ChatRequest(execution.spotId(),execution.question(),null)).answer();
        } catch(ApiException e) {
            boolean retryable=e.getStatus().is5xxServerError()&&!e.getMessage().contains("未配置");
            return store.fail(execution,retryable);
        } catch(Exception e) { return store.fail(execution,!(e instanceof IllegalArgumentException)); }
        try { store.complete(execution,content); return false; }
        catch(IllegalArgumentException e) { return store.fail(execution,false); }
    }
}
