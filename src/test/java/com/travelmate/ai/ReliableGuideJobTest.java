package com.travelmate.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelmate.common.ApiException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"app.jobs.dispatch-enabled=false","spring.datasource.url=jdbc:h2:mem:reliable-jobs;MODE=MySQL"})
@AutoConfigureMockMvc
class ReliableGuideJobTest {
    @Autowired GuideJobStore store;
    @Autowired GuideJobRepository jobs;
    @Autowired GuideOutboxRepository events;
    @Autowired GuideJobWorker worker;
    @Autowired PlatformTransactionManager tx;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockBean AiService ai;

    GuideJob draft(){var j=new GuideJob();j.setId(UUID.randomUUID().toString());j.setUserId(123L);j.setSpotId("1");j.setRequestHash("payload");return j;}
    GuideJob create(){return store.create(draft());}
    void due(String id,int generation){var e=events.findById(id+"-"+generation).orElseThrow();e.setAvailableAt(Instant.now().minusSeconds(1));events.saveAndFlush(e);}
    @Test void taskAndOutboxRollbackTogether(){
        var j=draft();new TransactionTemplate(tx).executeWithoutResult(s->{store.create(j);s.setRollbackOnly();});
        assertFalse(jobs.existsById(j.getId()));assertTrue(events.findByJobId(j.getId()).isEmpty());
    }
    @Test void sameKeyCannotChangePayload(){var a=create();var b=draft();b.setId(a.getId());b.setRequestHash("other");
        assertThrows(ApiException.class,()->store.create(b));assertEquals("payload",jobs.findById(a.getId()).orElseThrow().getRequestHash());}
    @Test void duplicateDeliveryCallsModelOnlyOnce(){
        var j=create();when(ai.explanation(any())).thenReturn(new AiDtos.ExplanationResponse("g","t","answer","s","pending","mock","mock"));
        var message=new AiDtos.GuideJobMessage(j.getId(),"1",null,null,0);worker.run(message);worker.run(message);
        verify(ai,times(1)).explanation(any());assertEquals("completed",jobs.findById(j.getId()).orElseThrow().getStatus());
    }
    @Test void concurrentClaimHasOneWinner() throws Exception {
        var j=create();var pool=Executors.newFixedThreadPool(2);var go=new CountDownLatch(1);
        Callable<GuideJobStore.Execution> action=()->{go.await();return store.claim(j.getId(),0);};
        try{var a=pool.submit(action);var b=pool.submit(action);go.countDown();assertEquals(1,(a.get()!=null?1:0)+(b.get()!=null?1:0));}finally{pool.shutdownNow();}
    }
    @Test void cancellationFencesLateCompletion(){var j=create();var e=store.claim(j.getId(),0);store.cancel(j.getId(),123L);
        assertFalse(store.complete(e,"late"));assertEquals("cancelled",jobs.findById(j.getId()).orElseThrow().getStatus());assertNull(store.claim(j.getId(),0));}
    @Test void expiredLeaseRecoversAndOldWorkerCannotWrite(){
        var j=create();var old=store.claim(j.getId(),0);var running=jobs.findById(j.getId()).orElseThrow();running.setLeaseUntil(Instant.now().minusSeconds(1));jobs.saveAndFlush(running);
        store.recover(j.getId());due(j.getId(),1);var fresh=store.claim(j.getId(),1);assertNotNull(fresh);
        assertFalse(store.complete(old,"stale"));assertTrue(store.complete(fresh,"new"));assertNull(store.claim(j.getId(),0));
    }
    @Test void retriesBackOffAndEventuallyFail(){
        var j=create();for(int generation=0;generation<3;generation++){
            due(j.getId(),generation);var e=store.claim(j.getId(),generation);assertNotNull(e);
            boolean terminal=store.fail(e,true);assertEquals(generation==2,terminal);
            if(generation<2)assertNull(store.claim(j.getId(),generation+1));
        }
        assertEquals("failed",jobs.findById(j.getId()).orElseThrow().getStatus());assertEquals(3,events.findByJobId(j.getId()).size());
    }
    @Test void redriveGetsNewGenerationAndIsIdempotentWhileQueued(){
        var j=create();var e=store.claim(j.getId(),0);store.fail(e,false);
        var r=store.retry(j.getId(),123L);assertEquals(1,r.getGeneration());assertEquals(0,r.getAttempts());
        assertEquals(1,store.retry(j.getId(),123L).getGeneration());assertNull(store.claim(j.getId(),0));assertNotNull(store.claim(j.getId(),1));
    }
    @Test void redriveIsOwnerOnly(){var j=create();assertThrows(ApiException.class,()->store.retry(j.getId(),456L));assertThrows(ApiException.class,()->store.cancel(j.getId(),456L));}
    @Test void unconfirmedPublishIsRescheduled(){var j=create();var delivery=store.reserve(j.getId()+"-0");assertNotNull(delivery);
        store.publishFailed(delivery);var event=events.findById(delivery.eventId()).orElseThrow();assertEquals("pending",event.getStatus());assertTrue(event.getAvailableAt().isAfter(Instant.now()));assertNull(store.reserve(delivery.eventId()));}
    @Test void expiredPublishLeaseRejectsStaleAcknowledgement(){
        var j=create();var old=store.reserve(j.getId()+"-0");var event=events.findById(old.eventId()).orElseThrow();event.setLeaseUntil(Instant.now().minusSeconds(1));events.saveAndFlush(event);
        var fresh=store.reserve(old.eventId());assertNotNull(fresh);store.sent(old);assertEquals("sending",events.findById(old.eventId()).orElseThrow().getStatus());store.sent(fresh);assertEquals("sent",events.findById(old.eventId()).orElseThrow().getStatus());
    }
    @Test void lostDeliveryIsRepublished(){var j=create();var d=store.reserve(j.getId()+"-0");store.sent(d);
        var pending=jobs.findById(j.getId()).orElseThrow();pending.setUpdatedAt(Instant.now().minusSeconds(121));jobs.saveAndFlush(pending);
        store.recover(j.getId());assertNotNull(store.reserve(d.eventId()));}
    @Test void exhaustedPublishingRequiresExplicitRedrive(){var j=create();var e=events.findById(j.getId()+"-0").orElseThrow();e.setPublishAttempts(10);events.saveAndFlush(e);
        assertNull(store.reserve(e.getId()));assertEquals("failed",jobs.findById(j.getId()).orElseThrow().getStatus());assertEquals(1,store.retry(j.getId(),123L).getGeneration());}
    @Test void deletingTaskCascadesOutbox(){var j=create();jobs.deleteById(j.getId());assertTrue(events.findByJobId(j.getId()).isEmpty());}
    @Test void concurrentHttpIdempotencyCreatesOneTask() throws Exception {
        String phone="16"+String.format("%09d",ThreadLocalRandom.current().nextInt(1_000_000_000));
        String bearer="Bearer "+json.readTree(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("phone",phone,"code","246810"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).at("/data/accessToken").asText();
        String key=UUID.randomUUID().toString();var pool=Executors.newFixedThreadPool(2);var go=new CountDownLatch(1);
        Callable<String> action=()->{go.await();return json.readTree(mvc.perform(post("/api/ai/explanations/async").header("Authorization",bearer).header("Idempotency-Key",key)
                .contentType(MediaType.APPLICATION_JSON).content("{\"spotId\":\"1\"}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).at("/data/jobId").asText();};
        String id;try{var a=pool.submit(action);var b=pool.submit(action);go.countDown();id=a.get();assertEquals(id,b.get());}finally{pool.shutdownNow();}
        assertEquals(1,events.findByJobId(id).size());
        mvc.perform(post("/api/ai/explanations/async").header("Authorization",bearer).header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content("{\"spotId\":\"2\"}")).andExpect(status().isConflict());
    }
}
