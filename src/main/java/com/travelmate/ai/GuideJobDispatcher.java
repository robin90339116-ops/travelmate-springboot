package com.travelmate.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.*;
import org.springframework.data.domain.PageRequest;
import java.time.Instant;
import java.util.concurrent.Executor;

@Configuration @EnableScheduling @Slf4j
@ConditionalOnProperty(name="app.jobs.dispatch-enabled",havingValue="true",matchIfMissing=true)
public class GuideJobDispatcher {
    private final GuideOutboxRepository outbox;
    private final GuideJobRepository jobs;
    private final GuideJobStore store;
    private final GuideJobWorker worker;
    private final Executor executor;
    private final ObjectProvider<AiJobProducer> producer;
    private final String mode;
    public GuideJobDispatcher(GuideOutboxRepository outbox,GuideJobRepository jobs,GuideJobStore store,
            GuideJobWorker worker,@Qualifier("guideExecutor") Executor executor,ObjectProvider<AiJobProducer> producer,
            @Value("${app.async.mode:local}") String mode) {
        this.outbox=outbox;this.jobs=jobs;this.store=store;this.worker=worker;this.executor=executor;this.producer=producer;this.mode=mode;
    }
    @Scheduled(fixedDelayString="${app.jobs.poll-ms:500}",initialDelayString="${app.jobs.initial-delay-ms:500}")
    public void dispatch() {
        for(String id:outbox.dueIds(Instant.now(),PageRequest.of(0,20))) {
            GuideJobStore.Delivery d;
            try { d=store.reserve(id); } catch(Exception e){log.warn("Outbox领取失败: {}",e.getClass().getSimpleName());continue;}
            if(d==null)continue;
            try {
                if("rabbit".equals(mode)) {
                    var sender=producer.getIfAvailable();if(sender==null)throw new IllegalStateException("MQ profile required");
                    sender.send(d.message());
                } else executor.execute(()->worker.run(d.message()));
                store.sent(d);
            } catch(Exception e) {
                store.publishFailed(d);
                log.warn("Outbox投递未确认，已安排重试: {}",e.getClass().getSimpleName());
            }
        }
    }
    @Scheduled(fixedDelayString="${app.jobs.recovery-ms:5000}",initialDelay=5000)
    public void recover() {
        Instant now=Instant.now();
        for(String id:jobs.recoveryIds(now,now.minusSeconds(120),PageRequest.of(0,50))) {
            try {store.recover(id);}catch(Exception e){log.warn("任务恢复失败: {}",e.getClass().getSimpleName());}
        }
    }
}
