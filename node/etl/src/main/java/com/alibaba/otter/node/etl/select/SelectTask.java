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

package com.alibaba.otter.node.etl.select;

import java.util.Date;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import org.slf4j.MDC;
import org.springframework.util.CollectionUtils;

import com.alibaba.otter.canal.common.CanalException;
import com.alibaba.otter.node.common.statistics.StatisticsClientService;
import com.alibaba.otter.node.etl.OtterConstants;
import com.alibaba.otter.node.etl.common.jmx.StageAggregation.AggregationItem;
import com.alibaba.otter.node.etl.common.pipe.PipeKey;
import com.alibaba.otter.node.etl.common.task.GlobalTask;
import com.alibaba.otter.node.etl.extract.SetlFuture;
import com.alibaba.otter.node.etl.select.exceptions.SelectException;
import com.alibaba.otter.node.etl.select.selector.Message;
import com.alibaba.otter.node.etl.select.selector.OtterSelector;
import com.alibaba.otter.node.etl.select.selector.OtterSelectorFactory;
import com.alibaba.otter.shared.arbitrate.model.EtlEventData;
import com.alibaba.otter.shared.arbitrate.model.TerminEventData;
import com.alibaba.otter.shared.common.model.config.channel.Channel;
import com.alibaba.otter.shared.common.model.config.enums.StageType;
import com.alibaba.otter.shared.common.model.statistics.delay.DelayCount;
import com.alibaba.otter.shared.common.utils.lock.BooleanMutex;
import com.alibaba.otter.shared.etl.model.DbBatch;
import com.alibaba.otter.shared.etl.model.EventData;
import com.alibaba.otter.shared.etl.model.Identity;
import com.alibaba.otter.shared.etl.model.RowBatch;

/**
 * select??????????????????????????????
 * 
 * <pre>
 * ???????????????
 * 1. ???????????????????????? 
 * ??????????????????3
 * ----------------------------------------------------------->?????????
 * | ProcessSelect
 * --> 1 
 *      --> 2
 *           -->3
 *               --> 1
 * | ProcessTermin
 *     ---> 1 ack
 *          ----> 2ack
 *                 ---> 3ack
 * a. ProcessSelect????????????????????????pool?????????????????????????????????ProcessTermin????????????termin??????
 * b. ???????????????s/e/t/l????????????????????????????????????????????????????????????ProcessSelect?????????????????????????????????????????????????????????????????????s/e/t/l??????
 * c. ProcessTermin?????????termin??????
 *    i. ???????????????????????????batchId/processId???????????????????????????????????????rollback??????.
 *    ii. ?????????terminType????????????????????????????????????????????????????????????????????????rollback??????
 * 
 * 2. ??????????????????
 * ??????????????????3
 * |-->1 --> 2 -->3(ing)
 * a. ??????1?????????,ProcessTermin????????????rollback?????????????????????2,3??????????????????. (?????????2,3????????????????????????s/e/t/l?????????)
 *    i. ??????2?????????????????????????????????2??????termin????????????????????????????????????ProcessSelect????????????????????????
 *    ii. ?????????2??????????????????????????????rollback?????????????????????s/e/t/l????????????
 * b. ????????????????????????????????????????????????ProcessSelect?????? (????????????????????????rollback???get???????????????????????????????????????)
 * 
 * 3. ????????????
 * a. Select????????????????????????mainstem??????????????????????????????????????????ProcessSelect/ProcessTermin??????
 * b. ProcessSelect/ProcessTermin???????????????????????????????????????????????????????????????mainstem??????????????????????????????????????????????????????????????????mainstem
 * c. ProcessSelect??????get????????????????????????ProcessTermin????????????????????????termin????????????????????????selector??????ack/rollback??????
 *      i. ?????????ProcessSelect??????get????????????????????????batch/termin/get???????????????????????????????????????????????????????????????
 * </pre>
 * 
 * @author jianghang 2012-7-31 ??????05:39:06
 * @version 4.1.0
 */
public class SelectTask extends GlobalTask {

    // ??????????????????
    private volatile boolean           isStart          = false;
    // ??????
    private StatisticsClientService    statisticsClientService;
    private OtterSelectorFactory       otterSelectorFactory;
    private OtterSelector<Message>     otterSelector;
    private ExecutorService            executor;
    private BlockingQueue<BatchTermin> batchBuffer      = new LinkedBlockingQueue<BatchTermin>(50); // ??????????????????????????????batch????????????
    private boolean                    needCheck        = false;
    private BooleanMutex               canStartSelector = new BooleanMutex(false);                 // ??????????????????????????????????????????????????????
    private AtomicInteger              rversion         = new AtomicInteger(0);
    private long                       lastResetTime    = new Date().getTime();

    public SelectTask(Long pipelineId){
        super(pipelineId);
    }

    public void run() {
        MDC.put(OtterConstants.splitPipelineLogFileKey, String.valueOf(pipelineId));
        try {
            while (running) {
                try {
                    if (isStart) {
                        boolean working = arbitrateEventService.mainStemEvent().check(pipelineId);
                        if (!working) {
                            stopup(false);
                        }

                        LockSupport.parkNanos(5 * 1000 * 1000L * 1000L); // 5??????????????????
                    } else {
                        startup();
                    }
                } catch (Throwable e) {
                    if (isInterrupt(e)) {
                        logger.info("INFO ## select is interrupt", e);
                        return;
                    } else {
                        logger.warn("WARN ## select is failed.", e);
                        sendRollbackTermin(pipelineId, e);

                        // sleep 10??????????????????
                        try {
                            Thread.sleep(10 * 1000);
                        } catch (InterruptedException e1) {
                        }
                    }
                }
            }
        } finally {
            arbitrateEventService.mainStemEvent().release(pipelineId);
        }
    }

    /**
     * ???????????????????????????mainstem????????????????????????????????????????????????
     * 
     * @throws InterruptedException
     */
    private void startup() throws InterruptedException {
        try {
            arbitrateEventService.mainStemEvent().await(pipelineId);
        } catch (Throwable e) {
            if (isInterrupt(e)) {
                logger.info("INFO ## this node is interrupt", e);
            } else {
                logger.warn("WARN ## this node is crashed.", e);
            }
            arbitrateEventService.mainStemEvent().release(pipelineId);
            return;
        }

        executor = Executors.newFixedThreadPool(2); // ??????????????????
        // ??????selector
        otterSelector = otterSelectorFactory.getSelector(pipelineId); // ???????????????selector
        otterSelector.start();

        canStartSelector.set(false);// ????????????false
        startProcessTermin();
        startProcessSelect();

        isStart = true;
    }

    private synchronized void stopup(boolean needInterrut) throws InterruptedException {
        if (isStart) {
            if (executor != null) {
                executor.shutdownNow();
            }

            if (otterSelector != null && otterSelector.isStart()) {
                otterSelector.stop();
            }

            if (needInterrut) {
                throw new InterruptedException();// ????????????????????????
            }

            isStart = false;
        }
    }

    /**
     * ????????????????????????
     */
    private void startProcessSelect() {
        executor.submit(new Runnable() {

            public void run() {
                MDC.put(OtterConstants.splitPipelineLogFileKey, String.valueOf(pipelineId));
                String currentName = Thread.currentThread().getName();
                Thread.currentThread().setName(createTaskName(pipelineId, "ProcessSelect"));
                try {
                    processSelect();
                } finally {
                    Thread.currentThread().setName(currentName);
                    MDC.remove(OtterConstants.splitPipelineLogFileKey);
                }
            }
        });

    }

    private void processSelect() {
        while (running) {
            try {
                // ??????ProcessTermin exhaust????????????
                // ProcessTermin????????????rollback???????????????????????????????????????permit????????????
                canStartSelector.get();

                // ????????????????????????????????????S????????????????????????????????????selector????????????????????????
                if (needCheck) {
                    checkContinueWork();
                }

                // ??????????????????????????????mananger?????????????????????????????????
                arbitrateEventService.toolEvent().waitForPermit(pipelineId);// ??????rollback??????????????????

                // ??????startVersion?????????????????????????????????rollback??????????????????????????????????????????rollback?????????rollback?????????????????????rollback???????????????
                // (????????????rollback????????????????????????????????????????????????????????????????????????get??????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????)
                // int startVersion = rversion.get();
                Message gotMessage = otterSelector.selector();

                // modify by ljh at 2012-09-10???startVersion??????????????????????????????????????????
                // ???????????? : (??????????????????bug)
                // // a.
                // ??????startVersion??????????????????????????????????????????rollback???????????????????????????selector????????????????????????????????????
                // // b. ?????????version????????????????????????????????????????????????????????????????????????????????????get
                // cursor???????????????????????????????????????????????????????????????????????????
                // ???????????? : (???????????????)
                // // a.
                // ????????????rollback???selector??????????????????rollback????????????????????????startVersion?????????????????????????????????????????????rollback?????????????????????????????????????????????
                // (?????????????????????????????????selector????????????startVersion???????????????)
                int startVersion = rversion.get();

                if (canStartSelector.state() == false) { // ??????????????????
                    // ????????????????????????????????????????????????????????????otterSelector.selector()???????????????????????????rollback?????????????????????
                    rollback(gotMessage.getId());
                    continue;
                }

                if (CollectionUtils.isEmpty(gotMessage.getDatas())) {// ??????????????????????????????????????????????????????????????????????????????
                    // ?????????????????????buffer??????????????????await termin???????????????????????????s/e/t/l??????
                    batchBuffer.put(new BatchTermin(gotMessage.getId(), false));
                    continue;
                }

                final EtlEventData etlEventData = arbitrateEventService.selectEvent().await(pipelineId);
                if (rversion.get() != startVersion) {// ???????????????????????????????????????rollback????????????????????????
                    logger.warn("rollback happend , should skip this data and get new message.");
                    canStartSelector.get();// ????????????rollback????????????
                    // ??????????????????????????????channel????????????????????????pause????????????????????????PAUSE??????***MemoryArbitrateEvent??????????????????????????????
                    Thread.sleep(10 * 1000);
                    arbitrateEventService.toolEvent().waitForPermit(pipelineId);
                    gotMessage = otterSelector.selector();// ???????????????????????????????????????????????????s/e/t/l
                }

                final Message message = gotMessage;
                final BatchTermin batchTermin = new BatchTermin(message.getId(), etlEventData.getProcessId());
                batchBuffer.put(batchTermin); // ?????????????????????buffer??????
                Runnable task = new Runnable() {

                    public void run() {
                        // ??????profiling??????
                        boolean profiling = isProfiling();
                        Long profilingStartTime = null;
                        if (profiling) {
                            profilingStartTime = System.currentTimeMillis();
                        }

                        MDC.put(OtterConstants.splitPipelineLogFileKey, String.valueOf(pipelineId));
                        String currentName = Thread.currentThread().getName();
                        Thread.currentThread().setName(createTaskName(pipelineId, "SelectWorker"));
                        try {
                            pipeline = configClientService.findPipeline(pipelineId);
                            List<EventData> eventData = message.getDatas();
                            long startTime = etlEventData.getStartTime();
                            if (!CollectionUtils.isEmpty(eventData)) {
                                startTime = eventData.get(0).getExecuteTime();
                            }

                            Channel channel = configClientService.findChannelByPipelineId(pipelineId);
                            RowBatch rowBatch = new RowBatch();
                            // ??????????????????
                            Identity identity = new Identity();
                            identity.setChannelId(channel.getId());
                            identity.setPipelineId(pipelineId);
                            identity.setProcessId(etlEventData.getProcessId());
                            rowBatch.setIdentity(identity);
                            // ??????????????????
                            for (EventData data : eventData) {
                                rowBatch.merge(data);
                            }

                            long nextNodeId = etlEventData.getNextNid();
                            List<PipeKey> pipeKeys = rowDataPipeDelegate.put(new DbBatch(rowBatch), nextNodeId);
                            etlEventData.setDesc(pipeKeys);
                            etlEventData.setNumber((long) eventData.size());
                            etlEventData.setFirstTime(startTime); // ??????????????????????????????
                            etlEventData.setBatchId(message.getId());

                            if (profiling) {
                                Long profilingEndTime = System.currentTimeMillis();
                                stageAggregationCollector.push(pipelineId,
                                    StageType.SELECT,
                                    new AggregationItem(profilingStartTime, profilingEndTime));
                            }
                            arbitrateEventService.selectEvent().single(etlEventData);
                        } catch (Throwable e) {
                            if (!isInterrupt(e)) {
                                logger.error(String.format("[%s] selectwork executor is error! data:%s",
                                    pipelineId,
                                    etlEventData), e);
                                sendRollbackTermin(pipelineId, e);
                            } else {
                                logger.info(String.format("[%s] selectwork executor is interrrupt! data:%s",
                                    pipelineId,
                                    etlEventData), e);
                            }
                        } finally {
                            Thread.currentThread().setName(currentName);
                            MDC.remove(OtterConstants.splitPipelineLogFileKey);
                        }
                    }
                };

                // ??????pending??????????????????????????????????????????
                SetlFuture extractFuture = new SetlFuture(StageType.SELECT,
                    etlEventData.getProcessId(),
                    pendingFuture,
                    task);
                executorService.execute(extractFuture);

            } catch (Throwable e) {
                if (!isInterrupt(e)) {
                    logger.error(String.format("[%s] selectTask is error!", pipelineId), e);
                    sendRollbackTermin(pipelineId, e);
                } else {
                    logger.info(String.format("[%s] selectTask is interrrupt!", pipelineId), e);
                    return;
                }
            }
        }
    }

    private void startProcessTermin() {
        executor.submit(new Runnable() {

            public void run() {
                MDC.put(OtterConstants.splitPipelineLogFileKey, String.valueOf(pipelineId));
                String currentName = Thread.currentThread().getName();
                Thread.currentThread().setName(createTaskName(pipelineId, "ProcessTermin"));
                try {
                    boolean lastStatus = true;
                    while (running) {
                        try {
                            // ??????????????????????????????????????????????????????termin??????
                            lastStatus = true;
                            // ??????????????????termin???????????????????????????????????????canal???????????????????????????
                            arbitrateEventService.terminEvent().exhaust(pipelineId);

                            batchBuffer.clear();// ??????????????????????????????batch????????????????????????batch?????????rollback???

                            // ??????????????????termin??????
                            while (running) {
                                if (batchBuffer.size() == 0) {
                                    // termin???????????????????????????????????????selector?????????????????????
                                    if (canStartSelector.state() == false) {
                                        otterSelector.rollback();// rollback????????????????????????????????????????????????ack?????????????????????????????????
                                    }

                                    lastStatus = true;
                                    canStartSelector.set(true);
                                }

                                BatchTermin batch = batchBuffer.take();
                                logger.info("start process termin : {}", batch.toString());
                                if (batch.isNeedWait()) {
                                    lastStatus = processTermin(lastStatus, batch.getBatchId(), batch.getProcessId());
                                } else {
                                    // ?????????wait??????????????????????????????batch?????????????????????ack
                                    if (lastStatus) {
                                        ack(batch.getBatchId());
                                        sendDelayReset(pipelineId);
                                    } else {
                                        rollback(batch.getBatchId());// ?????????selector????????????batch???rollback????????????
                                    }
                                }

                                logger.info("end process termin : {}  result : {}", batch.toString(), lastStatus);
                            }
                        } catch (CanalException e) {// ?????????????????????????????????retry,?????????????????????
                            logger.info(String.format("[%s] ProcessTermin has an error! retry...", pipelineId), e);
                            notifyRollback();
                        } catch (SelectException e) {// ?????????????????????????????????retry,?????????????????????
                            logger.info(String.format("[%s] ProcessTermin has an error! retry...", pipelineId), e);
                            notifyRollback();
                        } catch (Throwable e) {
                            if (isInterrupt(e)) {
                                logger.info(String.format("[%s] ProcessTermin is interrupted!", pipelineId), e);
                                return;
                            } else {
                                logger.error(String.format("[%s] ProcessTermin is error!", pipelineId), e);
                                notifyRollback();
                                sendRollbackTermin(pipelineId, e);
                            }
                        }

                        try {
                            Thread.sleep(30000); // sleep 30?????????termin?????????ready
                        } catch (InterruptedException e) {
                        }
                    }
                } finally {
                    Thread.currentThread().setName(currentName);
                    MDC.remove(OtterConstants.splitPipelineLogFileKey);
                }
            }

        });
    }

    private boolean processTermin(boolean lastStatus, Long batchId, Long processId) throws InterruptedException {
        int retry = 0;
        SelectException exception = null;
        TerminEventData terminData = null;
        while (retry++ < 30) {
            // ????????????????????????????????????Load??????termin???????????????????????????????????????????????????????????????termin??????????????????
            terminData = arbitrateEventService.terminEvent().await(pipelineId);
            Long terminBatchId = terminData.getBatchId();
            Long terminProcessId = terminData.getProcessId();

            if (terminBatchId == null && processId != -1L && !processId.equals(terminProcessId)) {
                // ??????manager??????rollback???terminBatchId?????????null????????????????????????
                exception = new SelectException("unmatched processId, SelectTask batchId = " + batchId
                                                + " processId = " + processId + " and Termin Event: "
                                                + terminData.toString());
                Thread.sleep(1000); // sleep 1????????????????????????
            } else if (terminBatchId != null && batchId != -1L && !batchId.equals(terminBatchId)) {
                exception = new SelectException("unmatched terminId, SelectTask batchId = " + batchId + " processId = "
                                                + processId + " and Termin Event: " + terminData.toString());
                Thread.sleep(1000); // sleep 1????????????????????????
            } else {
                exception = null; // batchId/processId??????????????????
                break;
            }
        }

        if (exception != null) {
            throw exception;
        }

        if (needCheck) {
            checkContinueWork();
        }

        boolean status = terminData.getType().isNormal();
        if (lastStatus == false && status == true) {
            // ?????????????????????????????????????????????????????????
            throw new SelectException(String.format("last status is rollback , but now [batchId:%d , processId:%d] is ack",
                batchId,
                terminData.getProcessId()));
        }

        if (terminData.getType().isNormal()) {
            ack(batchId);
            sendDelayStat(pipelineId, terminData.getEndTime(), terminData.getFirstTime());
        } else {
            rollback(batchId);
        }

        arbitrateEventService.terminEvent().ack(terminData); // ????????????????????????
        return status;
    }

    private void rollback(Long batchId) {
        notifyRollback();
        // otterSelector.rollback(batchId);
        otterSelector.rollback();// ???????????????rollback?????????mark??????????????????????????????????????????
    }

    private void ack(Long batchId) {
        canStartSelector.set(true);
        otterSelector.ack(batchId);
    }

    private void notifyRollback() {
        canStartSelector.set(false);
        rversion.incrementAndGet();// ??????????????????
    }

    /**
     * ?????????????????????????????????????????????mainstem????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????
     * ???????????????????????????
     */
    private void checkContinueWork() throws InterruptedException {
        boolean working = arbitrateEventService.mainStemEvent().check(pipelineId);
        if (!working) {
            logger.warn("mainstem is not run in this node");
            stopup(true);
        }

    }

    public void shutdown() {
        super.shutdown();

        if (executor != null) {
            executor.shutdownNow();
        }

        if (otterSelector != null && otterSelector.isStart()) {
            otterSelector.stop();
        }
    }

    public static class BatchTermin {

        private Long    batchId   = -1L;
        private Long    processId = -1L;
        private boolean needWait  = true;

        public BatchTermin(Long batchId, Long processId){
            this(batchId, processId, true);
        }

        public BatchTermin(Long batchId, boolean needWait){
            this(batchId, -1L, needWait);
        }

        public BatchTermin(Long batchId, Long processId, boolean needWait){
            this.batchId = batchId;
            this.processId = processId;
            this.needWait = needWait;
        }

        public Long getBatchId() {
            return batchId;
        }

        public void setBatchId(Long batchId) {
            this.batchId = batchId;
        }

        public Long getProcessId() {
            return processId;
        }

        public void setProcessId(Long processId) {
            this.processId = processId;
        }

        public boolean isNeedWait() {
            return needWait;
        }

        public void setNeedWait(boolean needWait) {
            this.needWait = needWait;
        }

        @Override
        public String toString() {
            return "BatchTermin [batchId=" + batchId + ", needWait=" + needWait + ", processId=" + processId + "]";
        }

    }

    private void sendDelayStat(long pipelineId, Long endTime, Long startTime) {
        DelayCount delayCount = new DelayCount();
        delayCount.setPipelineId(pipelineId);
        delayCount.setNumber(0L);// ????????????delayNumber
        if (startTime != null && endTime != null) {
            delayCount.setTime(endTime - startTime);// ?????????????????????????????????sysdate/now()
        }

        statisticsClientService.sendResetDelayCount(delayCount);
    }

    private void sendDelayReset(long pipelineId) {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastResetTime > 60 * 1000) {
            // 60??????manager??????????????????
            lastResetTime = currentTime;
            DelayCount delayCount = new DelayCount();
            delayCount.setPipelineId(pipelineId);
            delayCount.setNumber(0L);
            long delayTime = currentTime - otterSelector.lastEntryTime();
            delayCount.setTime(delayTime);
            statisticsClientService.sendResetDelayCount(delayCount);
        }
    }

    // ======================= setter / getter ===================

    public void setOtterSelectorFactory(OtterSelectorFactory otterSelectorFactory) {
        this.otterSelectorFactory = otterSelectorFactory;
    }

    public void setStatisticsClientService(StatisticsClientService statisticsClientService) {
        this.statisticsClientService = statisticsClientService;
    }

}
