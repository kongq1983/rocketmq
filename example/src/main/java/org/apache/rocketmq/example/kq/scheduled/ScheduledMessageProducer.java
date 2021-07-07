package org.apache.rocketmq.example.kq.scheduled;

import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.example.kq.Constants;

import java.time.LocalDateTime;


/**
 * @author kq
 * @date 2021-07-01 10:17
 * @since 2020-0630
 */
public class ScheduledMessageProducer {

    public static void main(String[] args) throws Exception {
        // Instantiate a producer to send scheduled messages
        DefaultMQProducer producer = new DefaultMQProducer("ExampleProducerGroup");
        // 用于调试
        producer.setSendMsgTimeout(300000);
        producer.setNamesrvAddr(Constants.DEFAULT_NAME_SERVER);
        // Launch producer
        producer.start();
        int totalMessagesToSend = 5;
        for (int i = 0; i < totalMessagesToSend; i++) {

            String messageBody = LocalDateTime.now()+",Hello scheduled message " + i;

            Message message = new Message("TestTopic", messageBody.getBytes());
            System.out.println(" "+messageBody);
            // This message will be delivered to consumer 10 seconds later.
            // 1s、 5s、 10s、 30s、 1m、 2m、 3m、 4m、 5m、 6m、 7m、 8m、 9m、 10m、 20m、 30m、 1h、 2h
            message.setDelayTimeLevel(3);
            // Send the message
            producer.send(message);
        }

        // Shutdown producer after use.
        producer.shutdown();
    }


}
