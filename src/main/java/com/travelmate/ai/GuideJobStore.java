package com.travelmate.ai;

import com.travelmate.common.ApiException;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** All state transitions and outbox writes commit together. Locks never span network calls. */
@Repository @RequiredArgsConstructor
public class GuideJobStore {
    private final GuideJobRepository jobs;
    private final GuideOutboxRepository outbox;
    private final EntityManager entities;
    @Value("${app.jobs.max-attempts:3}") private int maxAttempts;
    @Value("${app.jobs.execution-lease-seconds:180}") private int leaseSeconds;
    @Value("${app.jobs.max-publish-attempts:10}") private int maxPublishAttempts;

    public record Execution(String jobId, int generation, String token, String spotId,
            String style, String routeContext, String question) {}
    public record Delivery(String eventId, String token, AiDtos.GuideJobMessage message) {}

    @Transactional
    public GuideJob create(GuideJob job) {
        var existing=jobs.findById(job.getId());
        if(existing.isPresent()) { checkPayload(existing.get(),job.getRequestHash()); return existing.get(); }
        // persist (not merge) prevents a racing insert from overwriting an existing execution.
        entities.persist(job);
        enqueue(job,Instant.now());
        entities.flush();
        return job;
    }
    public static void checkPayload(GuideJob job,String hash) {
        if(!Objects.equals(job.getRequestHash(),hash))throw conflict("同一幂等键不能用于不同请求");
    }
    private static ApiException conflict(String message) {
        return new ApiException(org.springframework.http.HttpStatus.CONFLICT,40900,message);
    }
    private void enqueue(GuideJob job,Instant when) {
        var event=new GuideOutbox();event.setId(job.getId()+"-"+job.getGeneration());
        event.setJobId(job.getId());event.setGeneration(job.getGeneration());event.setAvailableAt(when);
        entities.persist(event);
    }
    private GuideJob owned(String id,Long uid) {
        var j=jobs.lockById(id).orElseThrow(()->ApiException.notFound("任务不存在"));
        if(!j.getUserId().equals(uid))throw ApiException.notFound("任务不存在");
        return j;
    }
    @Transactional
    public void cancel(String id,Long uid) {
        var j=owned(id,uid);
        if(j.getStatus().equals("queued")||j.getStatus().equals("running")) {
            j.setStatus("cancelled");j.setExecutionToken(null);j.setLeaseUntil(null);j.setUpdatedAt(Instant.now());
        }
    }
    @Transactional
    public GuideJob retry(String id,Long uid) {
        var j=owned(id,uid);
        // Retrying the HTTP request while a redrive is queued/running is a no-op.
        if(j.getStatus().equals("queued")||j.getStatus().equals("running"))return j;
        if(!j.getStatus().equals("failed"))throw conflict("只能重试失败任务");
        j.setAttempts(0);requeue(j,Instant.now());return j;
    }
    private void requeue(GuideJob j,Instant when) {
        j.setGeneration(j.getGeneration()+1);j.setStatus("queued");j.setExecutionToken(null);
        j.setLeaseUntil(null);j.setContent(null);j.setUpdatedAt(Instant.now());enqueue(j,when);
    }
    @Transactional
    public Execution claim(String id,int generation) {
        var j=jobs.lockById(id).orElse(null);
        if(j==null||!j.getStatus().equals("queued")||j.getGeneration()!=generation)return null;
        var event=outbox.findById(id+"-"+generation).orElse(null);
        if(event==null||event.getAvailableAt().isAfter(Instant.now()))return null;
        j.setStatus("running");j.setAttempts(j.getAttempts()+1);j.setError(null);
        j.setExecutionToken(UUID.randomUUID().toString());j.setLeaseUntil(Instant.now().plusSeconds(leaseSeconds));j.setUpdatedAt(Instant.now());
        return new Execution(id,generation,j.getExecutionToken(),j.getSpotId(),j.getStyle(),j.getRouteContext(),j.getQuestion());
    }
    private boolean owns(GuideJob j,Execution e) {
        return j!=null&&j.getStatus().equals("running")&&j.getGeneration()==e.generation()
                &&Objects.equals(j.getExecutionToken(),e.token())&&j.getLeaseUntil()!=null&&j.getLeaseUntil().isAfter(Instant.now());
    }
    @Transactional
    public boolean complete(Execution execution,String content) {
        var j=jobs.lockById(execution.jobId()).orElse(null);
        if(!owns(j,execution))return false;
        if(content==null||content.isBlank()||content.length()>32000)throw new IllegalArgumentException("生成内容无效或过长");
        j.setStatus("completed");j.setContent(content);j.setError(null);j.setExecutionToken(null);j.setLeaseUntil(null);j.setUpdatedAt(Instant.now());return true;
    }
    @Transactional
    public boolean fail(Execution execution,boolean retryable) {
        var j=jobs.lockById(execution.jobId()).orElse(null);
        if(!owns(j,execution))return false;
        j.setError(retryable?"上游调用失败":"请求或服务配置不可用");
        if(retryable&&j.getAttempts()<maxAttempts)requeue(j,Instant.now().plusSeconds(backoff(j.getAttempts())));
        else {j.setStatus("failed");j.setExecutionToken(null);j.setLeaseUntil(null);j.setUpdatedAt(Instant.now());}
        return j.getStatus().equals("failed");
    }
    private long backoff(int attempts){return Math.min(60,1L<<Math.min(attempts,5));}

    @Transactional
    public Delivery reserve(String eventId) {
        var snapshot=outbox.findById(eventId).orElse(null);if(snapshot==null)return null;
        // Every transition needing both rows locks job first, then outbox.
        var j=jobs.lockById(snapshot.getJobId()).orElse(null);
        if(j==null)return null;
        var e=outbox.lockById(eventId).orElse(null);if(e==null)return null;
        Instant now=Instant.now();
        boolean due=e.getStatus().equals("pending")&&!e.getAvailableAt().isAfter(now)
                ||e.getStatus().equals("sending")&&e.getLeaseUntil()!=null&&e.getLeaseUntil().isBefore(now);
        if(!due)return null;
        if(!j.getStatus().equals("queued")||j.getGeneration()!=e.getGeneration()) {e.setStatus("obsolete");return null;}
        if(e.getPublishAttempts()>=maxPublishAttempts) {
            e.setStatus("failed");j.setStatus("failed");j.setError("消息发送重试耗尽，请检查队列后重试");j.setUpdatedAt(now);return null;
        }
        e.setStatus("sending");e.setLeaseToken(UUID.randomUUID().toString());e.setLeaseUntil(now.plusSeconds(30));
        e.setPublishAttempts(e.getPublishAttempts()+1);
        return new Delivery(e.getId(),e.getLeaseToken(),new AiDtos.GuideJobMessage(j.getId(),j.getSpotId(),j.getStyle(),j.getRouteContext(),j.getGeneration()));
    }
    @Transactional
    public void sent(Delivery delivery) {
        var e=outbox.lockById(delivery.eventId()).orElse(null);
        if(e!=null&&e.getStatus().equals("sending")&&Objects.equals(e.getLeaseToken(),delivery.token())) {
            e.setStatus("sent");e.setLeaseToken(null);e.setLeaseUntil(null);e.setLastError(null);
        }
    }
    @Transactional
    public void publishFailed(Delivery delivery) {
        var e=outbox.lockById(delivery.eventId()).orElse(null);
        if(e!=null&&e.getStatus().equals("sending")&&Objects.equals(e.getLeaseToken(),delivery.token())) {
            e.setStatus("pending");e.setLeaseToken(null);e.setLeaseUntil(null);
            e.setAvailableAt(Instant.now().plusSeconds(backoff(e.getPublishAttempts())));e.setLastError("消息投递未获确认或执行队列已满");
        }
    }
    @Transactional
    public void recover(String id) {
        var j=jobs.lockById(id).orElse(null);if(j==null)return;
        Instant now=Instant.now();
        if(j.getStatus().equals("running")&&(j.getLeaseUntil()!=null?j.getLeaseUntil().isBefore(now):j.getUpdatedAt().isBefore(now.minusSeconds(120)))) {
            j.setError("执行租约过期");
            if(j.getAttempts()<maxAttempts)requeue(j,now.plusSeconds(backoff(j.getAttempts())));
            else {j.setStatus("failed");j.setExecutionToken(null);j.setLeaseUntil(null);j.setUpdatedAt(now);}
        } else if(j.getStatus().equals("queued")&&j.getUpdatedAt().isBefore(now.minusSeconds(120))) {
            var e=outbox.lockById(id+"-"+j.getGeneration()).orElse(null);
            if(e==null)enqueue(j,now); // Also recovers pre-outbox queued rows.
            else if(e.getStatus().equals("sent")) {e.setStatus("pending");e.setAvailableAt(now);}
            j.setUpdatedAt(now);
        }
    }
}
