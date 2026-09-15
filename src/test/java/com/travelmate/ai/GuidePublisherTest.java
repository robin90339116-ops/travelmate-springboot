package com.travelmate.ai;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.core.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class GuidePublisherTest {
    final AiDtos.GuideJobMessage message=new AiDtos.GuideJobMessage("id","1",null,null,0);
    @Test void acknowledgedAndRoutedPublishSucceeds(){var rabbit=mock(RabbitTemplate.class);
        doAnswer(i->{CorrelationData c=i.getArgument(3);c.getFuture().complete(new CorrelationData.Confirm(true,null));return null;})
            .when(rabbit).convertAndSend(eq(AiRabbitConfig.EXCHANGE),eq(AiRabbitConfig.ROUTING_KEY),eq(message),any(CorrelationData.class));
        assertDoesNotThrow(()->new AiJobProducer(rabbit).send(message));}
    @Test void negativeAcknowledgementFails(){var rabbit=mock(RabbitTemplate.class);
        doAnswer(i->{CorrelationData c=i.getArgument(3);c.getFuture().complete(new CorrelationData.Confirm(false,"nack"));return null;})
            .when(rabbit).convertAndSend(eq(AiRabbitConfig.EXCHANGE),eq(AiRabbitConfig.ROUTING_KEY),eq(message),any(CorrelationData.class));
        assertThrows(IllegalStateException.class,()->new AiJobProducer(rabbit).send(message));}
    @Test void returnedMessageFailsEvenWithAck(){var rabbit=mock(RabbitTemplate.class);
        doAnswer(i->{CorrelationData c=i.getArgument(3);c.setReturned(new ReturnedMessage(new Message(new byte[0]),312,"NO_ROUTE","exchange","route"));
            c.getFuture().complete(new CorrelationData.Confirm(true,null));return null;})
            .when(rabbit).convertAndSend(eq(AiRabbitConfig.EXCHANGE),eq(AiRabbitConfig.ROUTING_KEY),eq(message),any(CorrelationData.class));
        assertThrows(IllegalStateException.class,()->new AiJobProducer(rabbit).send(message));}
}
