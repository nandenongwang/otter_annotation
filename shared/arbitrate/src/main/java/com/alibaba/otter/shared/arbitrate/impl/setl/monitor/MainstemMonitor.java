/*
 * Copyright (C) 2010-2101 Alibaba Group Holding Limited.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.alibaba.otter.shared.arbitrate.impl.setl.monitor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.I0Itec.zkclient.IZkDataListener;
import org.I0Itec.zkclient.exception.ZkException;
import org.I0Itec.zkclient.exception.ZkInterruptedException;
import org.I0Itec.zkclient.exception.ZkNoNodeException;
import org.I0Itec.zkclient.exception.ZkNodeExistsException;
import org.apache.commons.lang.ClassUtils;
import org.apache.zookeeper.CreateMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.util.Assert;

import com.alibaba.otter.shared.arbitrate.exception.ArbitrateException;
import com.alibaba.otter.shared.arbitrate.impl.ArbitrateConstants;
import com.alibaba.otter.shared.arbitrate.impl.config.ArbitrateConfigUtils;
import com.alibaba.otter.shared.arbitrate.impl.setl.ArbitrateFactory;
import com.alibaba.otter.shared.arbitrate.impl.setl.ArbitrateLifeCycle;
import com.alibaba.otter.shared.arbitrate.impl.setl.helper.StagePathUtils;
import com.alibaba.otter.shared.arbitrate.impl.setl.monitor.listener.MainstemListener;
import com.alibaba.otter.shared.arbitrate.impl.zookeeper.ZooKeeperClient;
import com.alibaba.otter.shared.arbitrate.model.MainStemEventData;
import com.alibaba.otter.shared.common.model.config.channel.ChannelStatus;
import com.alibaba.otter.shared.common.utils.JsonUtils;
import com.alibaba.otter.shared.common.utils.lock.BooleanMutex;
import com.alibaba.otter.shared.common.utils.zookeeper.ZkClientx;
import com.google.common.collect.Lists;

/**
 * ????????????????????????active???????????????????????????standy????????????????????????
 * 
 * <pre>
 * 1. active????????????????????????????????????????????????????????????????????????
 * 2. active????????????????????????????????????active??????????????????standby???????????????????????????????????????
 * </pre>
 * 
 * @author jianghang 2012-10-1 ??????02:19:22
 * @version 4.1.0
 */
public class MainstemMonitor extends ArbitrateLifeCycle implements Monitor {

    private static final Logger        logger       = LoggerFactory.getLogger(MainstemMonitor.class);
    private ZkClientx                  zookeeper    = ZooKeeperClient.getInstance();
    private ScheduledExecutorService   delayExector = Executors.newScheduledThreadPool(1);
    private int                        delayTime    = 5;
    private volatile MainStemEventData activeData;
    private IZkDataListener            dataListener;
    private BooleanMutex               mutex        = new BooleanMutex(false);
    private volatile boolean           release      = false;
    private List<MainstemListener>     listeners    = Collections.synchronizedList(new ArrayList<MainstemListener>());

    public MainstemMonitor(Long pipelineId){
        super(pipelineId);
        // initMainstem();
        dataListener = new IZkDataListener() {

            public void handleDataChange(String dataPath, Object data) throws Exception {
                MDC.put(ArbitrateConstants.splitPipelineLogFileKey, String.valueOf(getPipelineId()));
                MainStemEventData mainStemData = JsonUtils.unmarshalFromByte((byte[]) data, MainStemEventData.class);
                if (!isMine(mainStemData.getNid())) {
                    mutex.set(false);
                }

                if (!mainStemData.isActive() && isMine(mainStemData.getNid())) { // ????????????????????????????????????????????????????????????active
                    release = true;
                    releaseMainstem();// ????????????mainstem
                }

                activeData = (MainStemEventData) mainStemData;
            }

            public void handleDataDeleted(String dataPath) throws Exception {
                MDC.put(ArbitrateConstants.splitPipelineLogFileKey, String.valueOf(getPipelineId()));
                mutex.set(false);
                if (!release && isMine(activeData.getNid())) {
                    // ???????????????active?????????????????????????????????????????????active??????
                    initMainstem();
                    // } else if (!isMine(activeData.getNid()) && !activeData.isActive()) {
                    // // ????????????????????????????????????????????????mainstem????????????active???????????????????????????????????????mainstem??????????????????????????????mainstem
                    // initMainstem();
                } else {
                    // ??????????????????delayTime??????????????????????????????zk??????????????????????????????????????????
                    delayExector.schedule(new Runnable() {

                        public void run() {
                            initMainstem();
                        }
                    }, delayTime, TimeUnit.SECONDS);
                }
            }

        };

        String path = StagePathUtils.getMainStem(getPipelineId());
        zookeeper.subscribeDataChanges(path, dataListener);
        MonitorScheduler.register(this, 5 * 60 * 1000L, 5 * 60 * 1000L); // 5??????????????????
    }

    public void reload() {
        if (logger.isDebugEnabled()) {
            logger.debug("## reload mainstem pipeline[{}]", getPipelineId());
        }

        try {
            initMainstem();
        } catch (Exception e) {// ???????????????
        }
    }

    public void initMainstem() {
        if (isStop()) {
            return;
        }

        PermitMonitor permitMonitor = ArbitrateFactory.getInstance(getPipelineId(), PermitMonitor.class);
        ChannelStatus status = permitMonitor.getChannelPermit(true);
        if (status.isStop()) {
            return; // ???????????????????????????
        }

        Long nid = ArbitrateConfigUtils.getCurrentNid();
        String path = StagePathUtils.getMainStem(getPipelineId());

        MainStemEventData data = new MainStemEventData();
        data.setStatus(MainStemEventData.Status.TAKEING);
        data.setPipelineId(getPipelineId());
        data.setNid(nid);// ???????????????nid
        // ?????????
        byte[] bytes = JsonUtils.marshalToByte(data);
        try {
            mutex.set(false);
            zookeeper.create(path, bytes, CreateMode.EPHEMERAL);
            activeData = data;
            processActiveEnter();// ??????????????????
            mutex.set(true);
        } catch (ZkNodeExistsException e) {
            bytes = zookeeper.readData(path, true);
            if (bytes == null) {// ??????????????????????????????????????????
                initMainstem();
            } else {
                activeData = JsonUtils.unmarshalFromByte(bytes, MainStemEventData.class);
                if (nid.equals(activeData.getNid())) { // reload???????????????????????????????????????????????????
                    mutex.set(true);
                }
            }
        }
    }

    public void destory() {
        super.destory();

        String path = StagePathUtils.getMainStem(getPipelineId());
        zookeeper.unsubscribeDataChanges(path, dataListener);

        delayExector.shutdownNow(); // ????????????
        releaseMainstem();
        MonitorScheduler.unRegister(this);
    }

    public boolean releaseMainstem() {
        if (check()) {
            String path = StagePathUtils.getMainStem(getPipelineId());
            zookeeper.delete(path);
            mutex.set(false);
            processActiveExit();
            return true;
        }

        return false;
    }

    public MainStemEventData getCurrentActiveData() {
        return activeData;
    }

    /**
     * ????????????????????????active?????????????????????active???????????????
     * 
     * @throws InterruptedException
     */
    public void waitForActive() throws InterruptedException {
        initMainstem();
        mutex.get();
    }

    /**
     * ?????????????????????
     */
    public boolean check() {
        String path = StagePathUtils.getMainStem(getPipelineId());
        try {
            byte[] bytes = zookeeper.readData(path);
            Long nid = ArbitrateConfigUtils.getCurrentNid();
            MainStemEventData eventData = JsonUtils.unmarshalFromByte(bytes, MainStemEventData.class);
            activeData = eventData;// ?????????????????????
            // ?????????nid???????????????
            boolean result = nid.equals(eventData.getNid());
            if (!result) {
                logger.warn("mainstem is running in node[{}] , but not in node[{}]", eventData.getNid(), nid);
            }
            return result;
        } catch (ZkNoNodeException e) {
            logger.warn("mainstem is not run any in node");
            return false;
        } catch (ZkInterruptedException e) {
            logger.warn("mainstem check is interrupt");
            Thread.interrupted();// ??????interrupt??????
            return check();
        } catch (ZkException e) {
            logger.warn("mainstem check is failed");
            return false;
        }
    }

    /**
     * ??????mainStem?????????????????????
     */
    public void single(MainStemEventData data) {
        Assert.notNull(data);
        Long nid = ArbitrateConfigUtils.getCurrentNid();
        if (!check()) {
            return;
        }

        data.setNid(nid);// ???????????????nid
        String path = StagePathUtils.getMainStem(data.getPipelineId());
        byte[] bytes = JsonUtils.marshalToByte(data);// ????????????????????????
        try {
            zookeeper.writeData(path, bytes);
        } catch (ZkException e) {
            throw new ArbitrateException("mainStem_single", data.toString(), e);
        }
        activeData = data;
    }

    // ====================== helper method ======================

    private boolean isMine(Long targetNid) {
        return targetNid.equals(ArbitrateConfigUtils.getCurrentNid());
    }

    public void addListener(MainstemListener listener) {
        if (logger.isDebugEnabled()) {
            logger.debug("## pipeline[{}] add listener [{}]", ClassUtils.getShortClassName(listener.getClass()));
        }

        this.listeners.add(listener);
    }

    public void removeListener(MainstemListener listener) {
        if (logger.isDebugEnabled()) {
            logger.debug("## remove listener [{}]", ClassUtils.getShortClassName(listener.getClass()));
        }

        this.listeners.remove(listener);
    }

    private void processActiveEnter() {
        for (final MainstemListener listener : Lists.newArrayList(listeners)) {
            try {
                listener.processActiveEnter();
            } catch (Exception e) {
                logger.error("processSwitchActive failed", e);
            }
        }
    }

    private void processActiveExit() {
        // ?????????????????????????????????????????????remove?????????????????????????????????java.util.ConcurrentModificationException????????????????????????????????????listener
        for (final MainstemListener listener : Lists.newArrayList(listeners)) {
            try {
                listener.processActiveExit();
            } catch (Exception e) {
                logger.error("processSwitchActive failed", e);
            }
        }
    }

    public void setDelayTime(int delayTime) {
        this.delayTime = delayTime;
    }

}
