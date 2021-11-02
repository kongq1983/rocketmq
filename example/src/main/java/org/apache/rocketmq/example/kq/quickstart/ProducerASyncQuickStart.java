package org.apache.rocketmq.example.kq.quickstart;

import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendCallback;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.example.kq.Constants;
import org.apache.rocketmq.remoting.common.RemotingHelper;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * @author kq
 * @date 2021-10-18 18:06
 * @since 2020-0630
 */
public class ProducerASyncQuickStart {

    public static void main(String[] args) throws Exception {
        //Instantiate with a producer group name.
        DefaultMQProducer producer = new DefaultMQProducer("QuickOrderGroup");
        // Specify name server addresses.
        producer.setNamesrvAddr(Constants.DEFAULT_NAME_SERVER);
        // 用于调试
        producer.setSendMsgTimeout(600000);
        //Launch the instance.
        producer.start();
        producer.setRetryTimesWhenSendAsyncFailed(0);

//        int messageCount = 100;
        int messageCount = 1;
        final CountDownLatch countDownLatch = new CountDownLatch(messageCount);
        for (int i = 0; i < messageCount; i++) {
            try {
                final int index = i;
                Message msg = new Message("Jodie_topic_1023",
                        "TagA",
                        "OrderID188",
                        "Hello world".getBytes(RemotingHelper.DEFAULT_CHARSET));
                producer.send(msg, new SendCallback() {
                    @Override
                    public void onSuccess(SendResult sendResult) {
                        countDownLatch.countDown();
                        System.out.printf("%-10d OK %s %n", index, sendResult.getMsgId());
                    }

                    @Override
                    public void onException(Throwable e) {
                        countDownLatch.countDown();
                        System.out.printf("%-10d Exception %s %n", index, e);
                        e.printStackTrace();
                    }
                });
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
//        countDownLatch.await(5, TimeUnit.SECONDS);
        countDownLatch.await(5, TimeUnit.HOURS);
        producer.shutdown();


    }

}
