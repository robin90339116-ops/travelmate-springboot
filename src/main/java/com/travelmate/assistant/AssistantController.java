package com.travelmate.assistant;

import com.travelmate.assistant.AssistantDtos.*;
import com.travelmate.common.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/ai/assistant") @RequiredArgsConstructor
public class AssistantController {
    private final AssistantService service;
    private final AssistantStore store;
    private final AssistantSessionRepository sessions;
    private final AssistantTraceRepository traces;
    @PostMapping("/sessions") public Result<?> create(@Valid @RequestBody CreateRequest r){return Result.ok(service.create(r));}
    @GetMapping("/sessions") public Result<?> list(){return Result.ok(sessions.findByUserIdOrderByUpdatedAtDesc(CurrentUser.id()));}
    @GetMapping("/sessions/{id}") public Result<?> get(@PathVariable String id){return Result.ok(service.owned(id));}
    @DeleteMapping("/sessions/{id}") public Result<?> delete(@PathVariable String id){store.delete(id,CurrentUser.id());return Result.ok();}
    @PostMapping("/sessions/{id}/messages") public Result<TurnResult> turn(@PathVariable String id,@Valid @RequestBody TurnRequest r){return Result.ok(service.turn(id,r));}
    @GetMapping("/traces/{id}") public Result<?> trace(@PathVariable String id){return Result.ok(traces.findByIdAndUserId(id,CurrentUser.id()).orElseThrow(()->ApiException.notFound("记录不存在")));}
}
