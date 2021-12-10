package org.apache.rocketmq.example.kq.quickstart;

import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeOrderlyContext;
import org.apache.rocketmq.client.consumer.listener.ConsumeOrderlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerOrderly;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.MessageQueueSelector;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.common.message.MessageQueue;
import org.apache.rocketmq.example.kq.Constants;
import org.apache.rocketmq.remoting.common.RemotingHelper;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * @author kq
 * @date 2021-10-18 18:06
 * @since 2020-0630
 */
public class ProducerQuickStart {

    public static void main(String[] args) throws Exception{

        //Instantiate with a producer group name.
        DefaultMQProducer producer = new DefaultMQProducer("QuickOrderGroup");
//        producer.setNamesrvAddr(Constants.DEFAULT_NAME_SERVER);
        producer.setNamesrvAddr("172.16.4.101:9876");
        // 用于调试
        producer.setSendMsgTimeout(300000);
        //Launch the instance.
        producer.start();
//        String[] tags = new String[] {"TagA", "TagB", "TagC", "TagD", "TagE"};
        String[] tags = new String[] {"TagA", "TagB", "TagC"};
        int size = 10; // 100
        for (int i = 0; i < size; i++) {
            int orderId = i % 10;
            //Create a message instance, specifying topic, tag and message body.
            String tag = tags[i % tags.length];
            Message msg = new Message("QuickOrder", tag, "KEY" + i,
                    ("Hello RocketMQ " + i+",tag="+tag).getBytes(RemotingHelper.DEFAULT_CHARSET));

            System.out.println("send i="+i+",tag="+tag);
            SendResult sendResult = producer.send(msg);

//            SendResult sendResult = producer.send(msg, new MessageQueueSelector() {
//                @Override
//                public MessageQueue select(List<MessageQueue> mqs, Message msg, Object arg) {
//                    Integer id = (Integer) arg;
//                    int index = id % mqs.size();
//                    System.out.println("id="+id+", index="+index+", mqs.size="+mqs.size());
//                    return mqs.get(index);
//                }
//            }, orderId);

            System.out.printf("%s%n", sendResult);

//            TimeUnit.SECONDS.sleep(10);
            TimeUnit.SECONDS.sleep(1);

        }

        TimeUnit.MINUTES.sleep(60);

        //server shutdown
        producer.shutdown();

    }

}
