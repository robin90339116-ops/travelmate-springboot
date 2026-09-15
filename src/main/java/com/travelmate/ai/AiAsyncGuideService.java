package com.travelmate.ai;
import com.travelmate.ai.AiDtos.*;
import com.travelmate.common.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.*;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.util.*;
import java.util.concurrent.*;
import java.time.Instant;

@Service
public class AiAsyncGuideService {
 private final AiService ai;
 private final GuideJobStore store;
 private final GuideJobWorker worker;
 private final com.fasterxml.jackson.databind.ObjectMapper json;
 private final String mode;
 private final GuideJobRepository jobs;
 private final ScheduledExecutorService poller=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"job-events");t.setDaemon(true);return t;});
 private record Subscription(String jobId,Long userId,String token,SseEmitter emitter){}
 private final Map<String,Subscription> subscriptions=new ConcurrentHashMap<>();
 private final com.travelmate.auth.SessionAuthenticator authenticator;

 public AiAsyncGuideService(AiService ai,GuideJobStore store,GuideJobWorker worker,com.fasterxml.jackson.databind.ObjectMapper json,
        @Value("${app.async.mode:local}") String mode,GuideJobRepository jobs,com.travelmate.auth.SessionAuthenticator authenticator){
  this.ai=ai;this.store=store;this.worker=worker;this.json=json;this.mode=mode;this.jobs=jobs;this.authenticator=authenticator;
  poller.scheduleWithFixedDelay(this::tick,1,1,TimeUnit.SECONDS);
 }
 @jakarta.annotation.PreDestroy public void close(){subscriptions.values().forEach(s->s.emitter().complete());poller.shutdownNow();}
 public GuideJobResponse submit(ExplanationRequest r){return submit(r,null,null,null);}
 public GuideJobResponse submit(ExplanationRequest r,String key){return submit(r,null,null,key);}
 public GuideJobResponse submitQuestion(ExplanationRequest r,Long teamId,String question){return submit(r,teamId,question,null);}
 public GuideJobResponse submitQuestion(ExplanationRequest r,Long teamId,String question,String key){return submit(r,teamId,question,key);}
 private GuideJobResponse submit(ExplanationRequest r,Long teamId,String question,String key){
  ai.requireSpot(r.spotId());
  if(key!=null&&!key.matches("[A-Za-z0-9._:-]{1,128}"))throw ApiException.badRequest("幂等键必须为1至128位字母、数字或._:-");
  Long uid=CurrentUser.id();
  GuideJob job=new GuideJob();job.setId(key==null?UUID.randomUUID().toString():UUID.nameUUIDFromBytes((uid+":"+key).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString());
  job.setUserId(uid);job.setTeamId(teamId);job.setQuestion(question);
  job.setSpotId(r.spotId());job.setStyle(r.style());job.setRouteContext(r.routeContext());
  try{job.setRequestHash(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
       .digest(json.writeValueAsBytes(java.util.Arrays.asList(r.spotId(),r.style(),r.routeContext(),teamId,question)))));}
  catch(Exception e){throw new IllegalStateException(e);}
  GuideJob saved;
  try{saved=store.create(job);}catch(org.springframework.dao.DataIntegrityViolationException e){
   saved=jobs.findById(job.getId()).orElseThrow(()->e);GuideJobStore.checkPayload(saved,job.getRequestHash());
  }
  return response(saved);
 }
 private GuideJobResponse response(GuideJob j){return new GuideJobResponse(j.getId(),j.getStatus(),"/api/ai/jobs/"+j.getId()+"/stream");}
 public GuideJobResponse retry(String id){return response(store.retry(id,CurrentUser.id()));}
 public record JobView(String jobId,String status,String content,String error,Instant updatedAt){}
 public JobView status(String id){return view(owned(id,CurrentUser.id()));}
 public void cancel(String id){store.cancel(id,CurrentUser.id());}
 private GuideJob owned(String id,Long uid){
  var j=jobs.findById(id).orElseThrow(()->ApiException.notFound("任务不存在"));
  if(!j.getUserId().equals(uid))throw ApiException.notFound("任务不存在");return j;
 }
 private JobView view(GuideJob j){return new JobView(j.getId(),j.getStatus(),j.getContent(),j.getError(),j.getUpdatedAt());}
 public SseEmitter subscribe(String id,String bearer){
  owned(id,CurrentUser.id());
  if(subscriptions.size()>=500)throw ApiException.serviceUnavailable("订阅数量已达上限");
  SseEmitter emitter=new SseEmitter(120000L);String key=UUID.randomUUID().toString();
  subscriptions.put(key,new Subscription(id,CurrentUser.id(),bearer.substring(7),emitter));
  emitter.onCompletion(()->subscriptions.remove(key));emitter.onTimeout(()->subscriptions.remove(key));emitter.onError(e->subscriptions.remove(key));
  return emitter;
 }
 private void tick(){
  subscriptions.forEach((key,s)->{
   try{
    authenticator.authenticate(s.token());
    var j=owned(s.jobId(),s.userId());
    boolean done=Set.of("completed","failed","cancelled").contains(j.getStatus());
    s.emitter().send(SseEmitter.event().name(done?"result":"progress").data(view(j)));
    if(done){subscriptions.remove(key);s.emitter().complete();}
   }catch(Exception e){subscriptions.remove(key);s.emitter().completeWithError(e);}
  });
 }
 public void runJob(GuideJobMessage message){
  if(worker.run(message)&&"rabbit".equals(mode))
   throw new org.springframework.amqp.AmqpRejectAndDontRequeueException("任务已达终态失败，可通过重试接口重驱");
 }
}
