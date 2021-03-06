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

package com.alibaba.otter.shared.arbitrate.impl;

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.I0Itec.zkclient.IZkConnection;
import org.I0Itec.zkclient.exception.ZkException;
import org.apache.commons.lang.StringUtils;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.KeeperException.NoNodeException;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.Stat;
import org.springframework.util.CollectionUtils;

import com.alibaba.otter.shared.arbitrate.ArbitrateViewService;
import com.alibaba.otter.shared.arbitrate.exception.ArbitrateException;
import com.alibaba.otter.shared.arbitrate.impl.manage.helper.ManagePathUtils;
import com.alibaba.otter.shared.arbitrate.impl.setl.helper.StageComparator;
import com.alibaba.otter.shared.arbitrate.impl.zookeeper.ZooKeeperClient;
import com.alibaba.otter.shared.arbitrate.model.EtlEventData;
import com.alibaba.otter.shared.arbitrate.model.MainStemEventData;
import com.alibaba.otter.shared.arbitrate.model.PositionEventData;
import com.alibaba.otter.shared.arbitrate.model.ProcessNodeEventData;
import com.alibaba.otter.shared.common.model.config.enums.StageType;
import com.alibaba.otter.shared.common.model.statistics.stage.ProcessStat;
import com.alibaba.otter.shared.common.model.statistics.stage.StageStat;
import com.alibaba.otter.shared.common.utils.JsonUtils;
import com.alibaba.otter.shared.common.utils.zookeeper.ZkClientx;
import com.alibaba.otter.shared.common.utils.zookeeper.ZooKeeperx;

/**
 * ???????????????????????????????????????????????????
 * 
 * @author jianghang 2011-9-27 ??????05:27:38
 * @version 4.0.0
 */
public class ArbitrateViewServiceImpl implements ArbitrateViewService {

    private static final String CANAL_PATH        = "/otter/canal/destinations/%s";
    private static final String CANAL_DATA_PATH   = CANAL_PATH + "/%s";
    private static final String CANAL_CURSOR_PATH = CANAL_PATH + "/%s/cursor";
    private ZkClientx           zookeeper         = ZooKeeperClient.getInstance();

    public MainStemEventData mainstemData(Long channelId, Long pipelineId) {
        String path = ManagePathUtils.getMainStem(channelId, pipelineId);
        try {
            byte[] bytes = zookeeper.readData(path);
            return JsonUtils.unmarshalFromByte(bytes, MainStemEventData.class);
        } catch (ZkException e) {
            return null;
        }
    }

    public Long getNextProcessId(Long channelId, Long pipelineId) {
        String processRoot = ManagePathUtils.getProcessRoot(channelId, pipelineId);
        IZkConnection connection = zookeeper.getConnection();
        // zkclient????????????stat????????????????????????????????????????????????zk????????????
        ZooKeeper orginZk = ((ZooKeeperx) connection).getZookeeper();

        Stat processParentStat = new Stat();
        // ???????????????process??????
        try {
            orginZk.getChildren(processRoot, false, processParentStat);
            return (Long) ((processParentStat.getCversion() + processParentStat.getNumChildren()) / 2L);
        } catch (Exception e) {
            return -1L;
        }
    }

    public List<ProcessStat> listProcesses(Long channelId, Long pipelineId) {
        List<ProcessStat> processStats = new ArrayList<ProcessStat>();
        String processRoot = ManagePathUtils.getProcessRoot(channelId, pipelineId);
        IZkConnection connection = zookeeper.getConnection();
        // zkclient????????????stat????????????????????????????????????????????????zk????????????
        ZooKeeper orginZk = ((ZooKeeperx) connection).getZookeeper();

        // ???????????????process??????
        List<String> processNodes = zookeeper.getChildren(processRoot);
        List<Long> processIds = new ArrayList<Long>();
        for (String processNode : processNodes) {
            processIds.add(ManagePathUtils.getProcessId(processNode));
        }

        Collections.sort(processIds);

        for (int i = 0; i < processIds.size(); i++) {
            Long processId = processIds.get(i);
            // ?????????process??????????????????
            ProcessStat processStat = new ProcessStat();
            processStat.setPipelineId(pipelineId);
            processStat.setProcessId(processId);

            List<StageStat> stageStats = new ArrayList<StageStat>();
            processStat.setStageStats(stageStats);
            try {
                String processPath = ManagePathUtils.getProcess(channelId, pipelineId, processId);
                Stat zkProcessStat = new Stat();
                List<String> stages = orginZk.getChildren(processPath, false, zkProcessStat);
                Collections.sort(stages, new StageComparator());

                StageStat prev = null;
                for (String stage : stages) {// ????????????process??????stage
                    String stagePath = processPath + "/" + stage;
                    Stat zkStat = new Stat();

                    StageStat stageStat = new StageStat();
                    stageStat.setPipelineId(pipelineId);
                    stageStat.setProcessId(processId);

                    byte[] bytes = orginZk.getData(stagePath, false, zkStat);
                    if (bytes != null && bytes.length > 0) {
                        // ????????????zookeeper??????data?????????manager????????????node???PipeKey???????????????????????????????????????????????????????????????????????????'@'??????
                        String json = StringUtils.remove(new String(bytes, "UTF-8"), '@');
                        EtlEventData data = JsonUtils.unmarshalFromString(json, EtlEventData.class);
                        stageStat.setNumber(data.getNumber());
                        stageStat.setSize(data.getSize());

                        Map exts = new HashMap();
                        if (!CollectionUtils.isEmpty(data.getExts())) {
                            exts.putAll(data.getExts());
                        }
                        exts.put("currNid", data.getCurrNid());
                        exts.put("nextNid", data.getNextNid());
                        exts.put("desc", data.getDesc());
                        stageStat.setExts(exts);
                    }
                    if (prev != null) {// ?????????start???????????????????????????????????????
                        stageStat.setStartTime(prev.getEndTime());
                    } else {
                        stageStat.setStartTime(zkProcessStat.getMtime()); // process?????????????????????,select
                                                                          // await??????????????????USED?????????
                    }
                    stageStat.setEndTime(zkStat.getMtime());
                    if (ArbitrateConstants.NODE_SELECTED.equals(stage)) {
                        stageStat.setStage(StageType.SELECT);
                    } else if (ArbitrateConstants.NODE_EXTRACTED.equals(stage)) {
                        stageStat.setStage(StageType.EXTRACT);
                    } else if (ArbitrateConstants.NODE_TRANSFORMED.equals(stage)) {
                        stageStat.setStage(StageType.TRANSFORM);
                        // } else if
                        // (ArbitrateConstants.NODE_LOADED.equals(stage)) {
                        // stageStat.setStage(StageType.LOAD);
                    }

                    prev = stageStat;
                    stageStats.add(stageStat);
                }

                // ?????????????????????????????????
                StageStat currentStageStat = new StageStat();
                currentStageStat.setPipelineId(pipelineId);
                currentStageStat.setProcessId(processId);
                if (prev == null) {
                    byte[] bytes = orginZk.getData(processPath, false, zkProcessStat);
                    if (bytes == null || bytes.length == 0) {
                        continue; // ?????????????????????????????????
                    }

                    ProcessNodeEventData nodeData = JsonUtils.unmarshalFromByte(bytes, ProcessNodeEventData.class);
                    if (nodeData.getStatus().isUnUsed()) {// process?????????,????????????
                        continue; // ?????????process
                    } else {
                        currentStageStat.setStage(StageType.SELECT);// select??????
                        currentStageStat.setStartTime(zkProcessStat.getMtime());
                    }
                } else {
                    // ???????????????????????????????????????stage
                    StageType stage = prev.getStage();
                    if (stage.isSelect()) {
                        currentStageStat.setStage(StageType.EXTRACT);
                    } else if (stage.isExtract()) {
                        currentStageStat.setStage(StageType.TRANSFORM);
                    } else if (stage.isTransform()) {
                        currentStageStat.setStage(StageType.LOAD);
                    } else if (stage.isLoad()) {// ??????????????????????????????
                        continue;
                    }

                    currentStageStat.setStartTime(prev.getEndTime());// ?????????????????????????????????????????????
                }

                if (currentStageStat.getStage().isLoad()) {// load??????????????????process??????
                    if (i == 0) {
                        stageStats.add(currentStageStat);
                    }
                } else {
                    stageStats.add(currentStageStat);// ?????????????????????
                }

            } catch (NoNodeException e) {
                // ignore
            } catch (KeeperException e) {
                throw new ArbitrateException(e);
            } catch (InterruptedException e) {
                // ignore
            } catch (UnsupportedEncodingException e) {
                // ignore
            }

            processStats.add(processStat);
        }

        return processStats;
    }

    public PositionEventData getCanalCursor(String destination, short clientId) {
        String path = String.format(CANAL_CURSOR_PATH, destination, String.valueOf(clientId));
        try {
            IZkConnection connection = zookeeper.getConnection();
            // zkclient????????????stat????????????????????????????????????????????????zk????????????
            ZooKeeper orginZk = ((ZooKeeperx) connection).getZookeeper();
            Stat stat = new Stat();
            byte[] bytes = orginZk.getData(path, false, stat);
            PositionEventData eventData = new PositionEventData();
            eventData.setCreateTime(new Date(stat.getCtime()));
            eventData.setModifiedTime(new Date(stat.getMtime()));
            eventData.setPosition(new String(bytes, "UTF-8"));
            return eventData;
        } catch (Exception e) {
            return null;
        }
    }

    public void removeCanalCursor(String destination, short clientId) {
        String path = String.format(CANAL_CURSOR_PATH, destination, String.valueOf(clientId));
        zookeeper.delete(path);
    }

    @Override
    public void removeCanal(String destination, short clientId) {
        String path = String.format(CANAL_DATA_PATH, destination, String.valueOf(clientId));
        zookeeper.deleteRecursive(path);
    }

    public void removeCanal(String destination) {
        String path = String.format(CANAL_PATH, destination);
        zookeeper.deleteRecursive(path);
    }

}
