package com.travelmate.ai;
import com.travelmate.common.*;
import com.travelmate.ai.AiDtos.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import java.time.Instant;

/** Personal, persistent per-place conversation. Reuses the cancellable durable job pipeline. */
@RestController @RequestMapping("/api/ai/conversation") @RequiredArgsConstructor
public class ConversationController {
 private final GuideJobRepository jobs;
 private final AiAsyncGuideService worker;
 private final AiService ai;
 private final ObjectMapper json;
 public record Request(@NotBlank String spotId,@NotBlank @Size(max=2000) String question){}
 public record Entry(String jobId,String question,String status,String content,String error,Instant createdAt){}
 @GetMapping public Result<?> history(@RequestParam String spotId){
  ai.requireSpot(spotId);
  return Result.ok(jobs.findTop20ByUserIdAndSpotIdAndTeamIdIsNullOrderByCreatedAtDesc(CurrentUser.id(),spotId).stream()
    .map(j->new Entry(j.getId(),j.getQuestion(),j.getStatus(),j.getContent(),j.getError(),j.getCreatedAt())).toList());
 }
 @PostMapping public Result<?> ask(@Valid @RequestBody Request r)throws Exception{
  ai.requireSpot(r.spotId());
  var prior=jobs.findTop20ByUserIdAndSpotIdAndTeamIdIsNullOrderByCreatedAtDesc(CurrentUser.id(),r.spotId()).stream()
    .filter(j->"completed".equals(j.getStatus())).limit(3).toList();
  var history=new ArrayList<Map<String,String>>();
  for(int i=prior.size()-1;i>=0;i--){var j=prior.get(i);history.add(Map.of("question",shortText(j.getQuestion()),"answer",shortText(j.getContent())));}
  String snapshot=json.writeValueAsString(history);
  while(snapshot.length()>3800&&!history.isEmpty()){history.remove(0);snapshot=json.writeValueAsString(history);}
  return Result.ok(worker.submitQuestion(new ExplanationRequest(r.spotId(),"brief",snapshot),null,r.question()));
 }
 private static String shortText(String s){return s==null?"":s.substring(0,Math.min(s.length(),400));}
}
