package org.apache.rocketmq.example.kq.selector.normal;

import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeOrderlyContext;
import org.apache.rocketmq.client.consumer.listener.ConsumeOrderlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerOrderly;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.example.kq.Constants;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * @author kq
 * @date 2021-05-20 16:58
 * @since 2020-0630
 */
public class OrderedConsumerTestFail {
    public static void main(String[] args) throws Exception {
        DefaultMQPushConsumer consumer = new DefaultMQPushConsumer("ExampleOrderGroup");
        consumer.setNamesrvAddr(Constants.DEFAULT_NAME_SERVER);

        consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);

        consumer.subscribe("TopicTest", "TagA || TagC || TagD");

        AtomicInteger atomicInteger = new AtomicInteger();

        consumer.registerMessageListener(new MessageListenerOrderly() {

            AtomicLong consumeTimes = new AtomicLong(0);
            @Override
            public ConsumeOrderlyStatus consumeMessage(List<MessageExt> msgs,
                                                       ConsumeOrderlyContext context) {
                context.setAutoCommit(false);
                System.out.printf(Thread.currentThread().getName() + " Receive New Messages: " + msgs + "%n");
                this.consumeTimes.incrementAndGet();

                if((atomicInteger.incrementAndGet()) > 25) {
                    return ConsumeOrderlyStatus.SUCCESS;
                }

                return ConsumeOrderlyStatus.SUSPEND_CURRENT_QUEUE_A_MOMENT;

            }
        });

        consumer.start();

        System.out.printf("Consumer Started.%n");
    }
}
