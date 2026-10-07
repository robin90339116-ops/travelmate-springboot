package com.travelmate.map;
import com.travelmate.common.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/places") @RequiredArgsConstructor
public class PlaceController {
 private final PlaceService places;
 public record ImportRequest(@NotBlank String contextToken){}
 @PostMapping("/import") public Result<?> select(@Valid @RequestBody ImportRequest request){return Result.ok(places.importSelected(request.contextToken(),CurrentUser.id()));}
 @GetMapping("/{id}") public Result<?> get(@PathVariable long id){return Result.ok(places.get(id));}
}
