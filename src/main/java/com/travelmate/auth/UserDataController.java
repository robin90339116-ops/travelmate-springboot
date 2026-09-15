package com.travelmate.auth;
import com.travelmate.common.*;
import com.travelmate.repository.*;
import com.travelmate.ai.GuideJobRepository;
import com.travelmate.team.TeamService;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController @RequestMapping("/api/data") @RequiredArgsConstructor
public class UserDataController {
 private final FavoriteRepository favorites;
 private final DeviceSessionRepository sessions;
 private final UserRepository users;
 private final TeamMemberRepository members;
 private final TeamService teams;
 private final GuideJobRepository jobs;
 private final com.travelmate.assistant.AssistantSessionRepository assistantSessions;
 private final com.travelmate.assistant.AssistantTraceRepository assistantTraces;
 @GetMapping("/export")
 public Result<?> export(){
  Long uid=CurrentUser.id();
  var user=users.findById(uid).orElseThrow(()->ApiException.notFound("用户不存在"));
  return Result.ok(Map.of("user",Map.of("id",uid,"phone",user.getPhone(),"displayName",user.getDisplayName()),
    "favorites",favorites.findByUserIdOrderByCreatedAtDesc(uid),"jobs",jobs.findByUserId(uid),"assistantSessions",assistantSessions.findByUserIdOrderByUpdatedAtDesc(uid),"assistantTraces",assistantTraces.findByUserId(uid)));
 }
 @DeleteMapping("/records") @Transactional
 public Result<Void> clear(){
  Long uid=CurrentUser.id();favorites.deleteAll(favorites.findByUserIdOrderByCreatedAtDesc(uid));
  jobs.deleteAll(jobs.findByUserId(uid));assistantTraces.deleteAll(assistantTraces.findByUserId(uid));assistantSessions.deleteAll(assistantSessions.findByUserIdOrderByUpdatedAtDesc(uid));return Result.ok();
 }
 @DeleteMapping("/account") @Transactional
 public Result<Void> delete(){
  Long uid=CurrentUser.id();
  for(var member:members.findByUserId(uid))teams.leave(uid,member.getTeamId());
  favorites.deleteAll(favorites.findByUserIdOrderByCreatedAtDesc(uid));jobs.deleteAll(jobs.findByUserId(uid));assistantTraces.deleteAll(assistantTraces.findByUserId(uid));assistantSessions.deleteAll(assistantSessions.findByUserIdOrderByUpdatedAtDesc(uid));
  sessions.deleteAll(sessions.findByUserIdOrderByLastActiveAtDesc(uid));users.deleteById(uid);
  return Result.ok();
 }
}
