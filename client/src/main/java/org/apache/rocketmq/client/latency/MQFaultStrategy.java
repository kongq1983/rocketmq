/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.rocketmq.client.latency;

import org.apache.rocketmq.client.impl.producer.TopicPublishInfo;
import org.apache.rocketmq.client.log.ClientLogger;
import org.apache.rocketmq.logging.InternalLogger;
import org.apache.rocketmq.common.message.MessageQueue;

public class MQFaultStrategy {
    private final static InternalLogger log = ClientLogger.getLog();
    private final LatencyFaultTolerance<String> latencyFaultTolerance = new LatencyFaultToleranceImpl();
    /** 默认false */
    private boolean sendLatencyFaultEnable = false;
    /**单位 ms */
    private long[] latencyMax = {50L, 100L, 550L, 1000L, 2000L, 3000L, 15000L}; /**单位 ms */
    private long[] notAvailableDuration = {0L, 0L, 30000L, 60000L, 120000L, 180000L, 600000L};

    public long[] getNotAvailableDuration() {
        return notAvailableDuration;
    }

    public void setNotAvailableDuration(final long[] notAvailableDuration) {
        this.notAvailableDuration = notAvailableDuration;
    }

    public long[] getLatencyMax() {
        return latencyMax;
    }

    public void setLatencyMax(final long[] latencyMax) {
        this.latencyMax = latencyMax;
    }

    public boolean isSendLatencyFaultEnable() {
        return sendLatencyFaultEnable;
    }

    public void setSendLatencyFaultEnable(final boolean sendLatencyFaultEnable) {
        this.sendLatencyFaultEnable = sendLatencyFaultEnable;
    }

    public MessageQueue selectOneMessageQueue(final TopicPublishInfo tpInfo, final String lastBrokerName) {
        if (this.sendLatencyFaultEnable) { // latencyFaultTolerance，它维护了那些消息发送延迟较高的brokers的信息
            try {
                int index = tpInfo.getSendWhichQueue().getAndIncrement(); // 如果是第1次 生成随机数，以后都是+1
                for (int i = 0; i < tpInfo.getMessageQueueList().size(); i++) {
                    int pos = Math.abs(index++) % tpInfo.getMessageQueueList().size();
                    if (pos < 0)
                        pos = 0;
                    MessageQueue mq = tpInfo.getMessageQueueList().get(pos);
                    if (latencyFaultTolerance.isAvailable(mq.getBrokerName())) // 判断该Broker是否可用，不可用继续下一个  现在时间-开始时间 >=0
                        return mq;
                } // 上面代码就是找到1个可用的MessageQueue，其实就是延迟时间已到的broker
                // 找不到，则取相对来说较近的那个，也不是第1个，如果只有1个，就返回这个，如果>1，排号序，第一次取1半之前的随机1个，以后都是从当前位置按顺序循环下去(前1半)
                final String notBestBroker = latencyFaultTolerance.pickOneAtLeast();
                int writeQueueNums = tpInfo.getQueueIdByBroker(notBestBroker); // 在queueData中是否存在notBestBroker 如果存在则返回该queue的writeQueueNums 不存在则返回-1
                if (writeQueueNums > 0) { // 存在
                    final MessageQueue mq = tpInfo.selectOneMessageQueue();
                    if (notBestBroker != null) {
                        mq.setBrokerName(notBestBroker);
                        mq.setQueueId(tpInfo.getSendWhichQueue().getAndIncrement() % writeQueueNums);
                    }
                    return mq;
                } else { // 不可写，从latencyFaultTolerance删除该notBestBroker
                    latencyFaultTolerance.remove(notBestBroker);
                }
            } catch (Exception e) {
                log.error("Error occurred when selecting message queue", e);
            }
            // (随机数+1)%messageQueueList.size    (以后当前数+1)%messageQueueList.size
            return tpInfo.selectOneMessageQueue();
        }
        // 默认走这里 (随机数+1)%messageQueueList.size    (以后当前数+1)%messageQueueList.size
        return tpInfo.selectOneMessageQueue(lastBrokerName);
    }
    // TODO updateFaultItem    currentLatency = System.currentTimeMillis() - responseFuture.getBeginTimestamp() = 请求花了多少时间
    public void updateFaultItem(final String brokerName, final long currentLatency, boolean isolation) {
        if (this.sendLatencyFaultEnable) { // 开启延迟容错  isolation=false
            long duration = computeNotAvailableDuration(isolation ? 30000 : currentLatency); // isolation=true=30000  isolation=false 根据超时时间得到对应的
            this.latencyFaultTolerance.updateFaultItem(brokerName, currentLatency, duration);
        }
    } // currentLatency(ms) 花费时间 = System.currentTimeMillis() startTime - System.currentTimeMillis() endTime
    /** 返回duration  先判断currentLatency在latencyMax那个位置  然后返回 notAvailableDuration的该位置的值  */
    private long computeNotAvailableDuration(final long currentLatency) { // TODO updateFaultItem
        for (int i = latencyMax.length - 1; i >= 0; i--) { // 假设latencyMax=3500   则返回180000L
            if (currentLatency >= latencyMax[i]) // latencyMax: {50L, 100L, 550L, 1000L, 2000L, 3000L, 15000L}
                return this.notAvailableDuration[i]; // {0L, 0L, 30000L, 60000L, 120000L, 180000L, 600000L} // ms
        }

        return 0;
    }
}
