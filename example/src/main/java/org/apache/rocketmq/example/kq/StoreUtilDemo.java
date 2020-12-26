package org.apache.rocketmq.example.kq;

import org.apache.rocketmq.store.StoreUtil;

/**
 * @author kq
 * @date 2020-12-22 15:23
 * @since 2020-0630
 */
public class StoreUtilDemo {

    public static void main(String[] args) {
        long num = StoreUtil.getTotalPhysicalMemorySize();
        System.out.println("num="+num);
    }

}
