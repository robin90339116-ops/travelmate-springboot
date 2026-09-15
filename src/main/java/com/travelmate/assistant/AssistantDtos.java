package com.travelmate.assistant;

import jakarta.validation.constraints.*;
import java.util.List;
import java.util.Map;

public final class AssistantDtos {
    private AssistantDtos() {}
    public record CreateRequest(@NotBlank @Size(max=32) String cityKey,
            @Min(15) @Max(480) Integer durationMinutes, @Size(max=200) String interests,
            @Size(max=80) String companions) {}
    public record TurnRequest(@NotBlank @Size(max=2000) String message,
            @Min(15) @Max(480) Integer durationMinutes, @Size(max=200) String interests,
            @Size(max=80) String companions) {}
    public record Constraints(String cityKey, int durationMinutes, String interests, String companions) {}
    public record Message(String role, String content) {}
    public record Citation(String id, String title, String sourceUrl, String updatedAt,
            String sourceStatus, String excerpt) {}
    public record Stop(String id, String name, Integer stayMinutes, String openTime, String sourceStatus) {}
    public record RouteCard(List<Stop> stops, Integer totalMinutes, int budgetMinutes,
            String timingStatus, List<String> warnings, List<String> removedSpotIds) {}
    public record ComparisonRow(String id, String name, String category, String suggestedStay,
            String openingHours, String tags, String sourceStatus) {}
    public record Answer(String text, List<Citation> citations, RouteCard route,
            List<ComparisonRow> comparison, boolean fallback) {}
    public record ToolTrace(String name, Map<String,Object> arguments, String status, long elapsedMs) {}
    public record TurnResult(String sessionId, String traceId, String promptVersion, String model,
            Constraints constraints, Answer answer, List<ToolTrace> tools, long elapsedMs) {}
}
