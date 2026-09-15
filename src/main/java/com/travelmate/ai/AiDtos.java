package com.travelmate.ai;

import java.io.Serializable;

public final class AiDtos {

    private AiDtos() {
    }

    public record ExplanationRequest(@jakarta.validation.constraints.NotBlank String spotId,
            @jakarta.validation.constraints.Size(max=32) String style,
            @jakarta.validation.constraints.Size(max=4000) String routeContext) {
    }

    public record ExplanationResponse(
            String explanationId, String title, String content,
            String sourceName, String factType, String provider, String model) {
    }

    public record ChatRequest(String spotId, String question, String teamContext) {
    }

    public record ChatResponse(String answer, String sourceName, String provider, String model) {
    }

    public record VisionRequest(String spotId, String mode, String mediaUrl, String question) {
    }

    public record VisionResponse(String mode, String mediaType, String answer, String provider, String model) {
    }

    /** 异步讲解任务提交返回。 */
    public record GuideJobResponse(String jobId, String status, String streamUrl) {
    }

    /** 进入 MQ 的任务消息。 */
    public record GuideJobMessage(String jobId, String spotId, String style, String routeContext, Integer generation)
            implements Serializable {
        public GuideJobMessage(String jobId,String spotId,String style,String routeContext) {
            this(jobId,spotId,style,routeContext,0);
        }
    }
}
