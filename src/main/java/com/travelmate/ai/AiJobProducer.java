package com.travelmate.ai;

import com.travelmate.ai.AiDtos.GuideJobMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import java.util.concurrent.TimeUnit;

@Component @Profile("mq") @RequiredArgsConstructor
public class AiJobProducer {
    private final RabbitTemplate rabbitTemplate;
    public void send(GuideJobMessage message) {
        CorrelationData correlation=new CorrelationData(message.jobId()+"-"+message.generation());
        rabbitTemplate.convertAndSend(AiRabbitConfig.EXCHANGE,AiRabbitConfig.ROUTING_KEY,message,correlation);
        try {
            var confirm=correlation.getFuture().get(5,TimeUnit.SECONDS);
            if(!confirm.isAck()||correlation.getReturned()!=null)throw new IllegalStateException("消息未确认或无法路由");
        } catch(InterruptedException e) {Thread.currentThread().interrupt();throw new IllegalStateException("投递被中断",e);}
        catch(java.util.concurrent.ExecutionException|java.util.concurrent.TimeoutException e){throw new IllegalStateException("消息确认失败",e);}
    }
}
