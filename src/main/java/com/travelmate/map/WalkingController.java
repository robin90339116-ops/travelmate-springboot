package com.travelmate.map;
import com.travelmate.common.*;
import org.springframework.web.bind.annotation.*;
import lombok.RequiredArgsConstructor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
@RestController @RequestMapping("/api/location") @RequiredArgsConstructor
public class WalkingController {
 private final WalkingService walking;
 public record Request(@NotBlank String contextToken,LiveLocationService.Position position){}
 @PostMapping("/walking") public Result<?> route(@Valid @RequestBody Request r){return Result.ok(walking.route(r.contextToken(),CurrentUser.id(),r.position()));}
}
