package com.travelmate.ai;

import com.travelmate.ai.AiDtos.*;
import com.travelmate.common.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiController {

    private final AiService aiService;
    private final AiAsyncGuideService asyncGuideService;

    @PostMapping("/explanations")
    public Result<ExplanationResponse> explanation(@jakarta.validation.Valid @RequestBody ExplanationRequest request) {
        return Result.ok(aiService.explanation(request));
    }

    @PostMapping("/chat")
    public Result<ChatResponse> chat(@RequestBody ChatRequest request) {
        return Result.ok(aiService.chat(request));
    }

    @PostMapping("/vision")
    public Result<VisionResponse> vision(@RequestBody VisionRequest request) {
        return Result.ok(aiService.vision(request));
    }

    /** 提交异步讲解生成任务,返回 jobId 与 SSE 订阅地址。 */
    @PostMapping("/explanations/async")
    public Result<GuideJobResponse> submitAsync(@jakarta.validation.Valid @RequestBody ExplanationRequest request,
            @RequestHeader(value="Idempotency-Key",required=false) String key) {
        return Result.ok(asyncGuideService.submit(request,key));
    }

    /** 订阅任务进度(Server-Sent Events)。 */
    @GetMapping("/jobs/{jobId}/stream")
    public SseEmitter stream(@PathVariable String jobId, @RequestHeader("Authorization") String authorization) {
        return asyncGuideService.subscribe(jobId, authorization);
    }

    @GetMapping("/jobs/{jobId}")
    public Result<AiAsyncGuideService.JobView> status(@PathVariable String jobId) {
        return Result.ok(asyncGuideService.status(jobId));
    }
    @PostMapping("/jobs/{jobId}/retry")
    public Result<GuideJobResponse> retry(@PathVariable String jobId) {return Result.ok(asyncGuideService.retry(jobId));}

    @DeleteMapping("/jobs/{jobId}")
    public Result<Void> cancel(@PathVariable String jobId) {
        asyncGuideService.cancel(jobId);return Result.ok();
    }
}
