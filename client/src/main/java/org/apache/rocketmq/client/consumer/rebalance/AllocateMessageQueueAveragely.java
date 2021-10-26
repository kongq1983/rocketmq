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
package org.apache.rocketmq.client.consumer.rebalance;

import java.util.ArrayList;
import java.util.List;
import org.apache.rocketmq.client.consumer.AllocateMessageQueueStrategy;
import org.apache.rocketmq.client.log.ClientLogger;
import org.apache.rocketmq.logging.InternalLogger;
import org.apache.rocketmq.common.message.MessageQueue;

/**
 * Average Hashing queue algorithm
 */
public class AllocateMessageQueueAveragely implements AllocateMessageQueueStrategy {
    private final InternalLogger log = ClientLogger.getLog();

    @Override // mqAll : MessageQueue列表
    public List<MessageQueue> allocate(String consumerGroup, String currentCID, List<MessageQueue> mqAll,
        List<String> cidAll) {
        if (currentCID == null || currentCID.length() < 1) { // 当前消费者
            throw new IllegalArgumentException("currentCID is empty");
        }
        if (mqAll == null || mqAll.isEmpty()) {
            throw new IllegalArgumentException("mqAll is null or mqAll empty");
        }
        if (cidAll == null || cidAll.isEmpty()) {
            throw new IllegalArgumentException("cidAll is null or cidAll empty");
        }

        List<MessageQueue> result = new ArrayList<MessageQueue>();
        if (!cidAll.contains(currentCID)) { // 判断是否存在
            log.info("[BUG] ConsumerGroup: {} The consumerId: {} not in cidAll: {}",
                consumerGroup,
                currentCID,
                cidAll);
            return result;
        }
        //基本原则，每个队列只能被一个consumer消费
        int index = cidAll.indexOf(currentCID); // 当前消费者在cidAll中位置
        int mod = mqAll.size() % cidAll.size(); // 5%100 == 5 当messageQueue个数小于等于consume的时候，排在前面（在list中的顺序）的consumer消费一个queue，index大于messageQueue之后的consumer消费不到queue，也就是为0
        int averageSize = // 如果MessageQueue.size() < = customer的数量，则=1
            mqAll.size() <= cidAll.size() ? 1 : (mod > 0 && index < mod ? mqAll.size() / cidAll.size()
                + 1 : mqAll.size() / cidAll.size()); // 8/3
        int startIndex = (mod > 0 && index < mod) ? index * averageSize : index * averageSize + mod;
        int range = Math.min(averageSize, mqAll.size() - startIndex);
        for (int i = 0; i < range; i++) {
            result.add(mqAll.get((startIndex + i) % mqAll.size()));
        }
        return result;
    }

    @Override
    public String getName() {
        return "AVG";
    }
}
// 场景1
// 假设 MessageQueue有q1,q2,q3,q4,q4,q6,q7,q8  cidAll有 c1,c2,c3
// c1逻辑
// index=0 mod=2  averageSize=(mod > 0 && index < mod ? mqAll.size() / cidAll.size() + 1) = (8/3+1)=3
// range=Math.min(averageSize, mqAll.size() - startIndex)=min(3,8)=3   startIndex=index * averageSize=0×3=0   范围就是[0,3)
// c2逻辑
// index=1 mod=2  averageSize=(mod > 0 && index < mod ? mqAll.size() / cidAll.size() + 1) = (8/3+1)=3
// range=Math.min(averageSize, mqAll.size() - startIndex)=min(3,8)=3   startIndex=index * averageSize=1×3=3   范围就是[3,6)
// c3逻辑
// index=2 mod=2  averageSize=(mod > 0 && index < mod ? mqAll.size() / cidAll.size() ) = (8/3)=2  注意这里averageSize是没+1的，是因为index = mod，走这个逻辑mqAll.size() / cidAll.size()
// range=Math.min(averageSize, mqAll.size() - startIndex)=min(3,8)=2   startIndex=index * averageSize + mod = 2×2+2 =6   范围就是[6,8)
// 最终结果:  c1:[q1,q2,q3]  c2:[q4,q5,q6]  c3: [q7,q8]

// 场景2
// 如果MessageQueue有q1,q2,q3   cidAll有 c1,c2,c3,c4,c5
// 因为 mqAll.size() <= cidAll.size() 所以averageSize=1，每个消费者最多1个MessageQueue
// c1逻辑  index=0  mod=3  averageSize=1  range=1 startIndex=index * averageSize=0×1=0    所以c1:[q1]
// c2逻辑  index=1  mod=3  averageSize=1  range=1 startIndex=index * averageSize=1×1=1    所以c2:[q2]
// c3逻辑  index=2  mod=3  averageSize=1  range=1 startIndex=index * averageSize=2×1=1    所以c3:[q3]