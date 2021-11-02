package org.apache.rocketmq.example.kq.countdownlatch2;

import org.apache.rocketmq.common.CountDownLatch2;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

/**
 * @author kq
 * @date 2021-11-02 14:01
 * @since 2020-0630
 */
public class CountDownLatch2Test {


    public static void main(String[] args) {

        CountDownLatch2 waitPoint = new CountDownLatch2(1);
        System.out.println(LocalDateTime.now()+", before reset");


        waitPoint.countDown();
        Runnable runnable = ()-> {
            try {
                TimeUnit.SECONDS.sleep(5);
                waitPoint.countDown();
                System.out.println(LocalDateTime.now()+", execute countDown");
            }catch (Exception e ){
                e.printStackTrace();
            }
        };

//        new Thread(runnable).start();

//        重置state 如果没有重置标志位，则直接结束，重置标志位，则await会阻塞
//        waitPoint.reset(); // CountDownLatch2 setState(state)

        try {
            waitPoint.await(10, TimeUnit.SECONDS);
            System.out.println(LocalDateTime.now()+", after await");
        } catch (InterruptedException e) {
            e.printStackTrace();
        } finally {

        }



    }

}
