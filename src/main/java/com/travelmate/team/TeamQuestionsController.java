package com.travelmate.team;
import com.travelmate.ai.*;
import com.travelmate.common.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

@RestController @RequiredArgsConstructor @RequestMapping("/api/teams/{teamId}/questions")
public class TeamQuestionsController {
 private final TeamService teams;
 private final AiAsyncGuideService ai;
 private final GuideJobRepository jobs;
 public record Question(@NotBlank String spotId,@NotBlank @Size(max=2000) String question){}
 public record SharedAnswer(String jobId,Long authorId,String question,String status,String answer,String error){}
 @PostMapping
 public Result<AiDtos.GuideJobResponse> ask(@PathVariable Long teamId,@Valid @RequestBody Question question,@RequestHeader(value="Idempotency-Key",required=false) String key){
  teams.requireMember(teamId,CurrentUser.id());
  return Result.ok(ai.submitQuestion(new AiDtos.ExplanationRequest(question.spotId(),"brief",null),teamId,question.question(),key));
 }
 @GetMapping
 public Result<List<SharedAnswer>> list(@PathVariable Long teamId){
  teams.requireMember(teamId,CurrentUser.id());
  return Result.ok(jobs.findByTeamIdOrderByCreatedAtAsc(teamId).stream().map(j->new SharedAnswer(j.getId(),j.getUserId(),j.getQuestion(),j.getStatus(),j.getContent(),j.getError())).toList());
 }
 @DeleteMapping("/{jobId}")
 public Result<Void> cancel(@PathVariable Long teamId,@PathVariable String jobId){
  teams.requireMember(teamId,CurrentUser.id());
  var job=jobs.findById(jobId).orElseThrow(()->ApiException.notFound("问题不存在"));
  if(!teamId.equals(job.getTeamId()))throw ApiException.notFound("问题不存在");
  ai.cancel(jobId);return Result.ok();
 }
}
