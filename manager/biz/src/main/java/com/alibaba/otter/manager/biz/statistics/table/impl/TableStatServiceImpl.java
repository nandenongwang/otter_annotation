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

package com.alibaba.otter.manager.biz.statistics.table.impl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;

import com.alibaba.otter.manager.biz.statistics.table.TableStatService;
import com.alibaba.otter.manager.biz.statistics.table.dal.TableHistoryStatDAO;
import com.alibaba.otter.manager.biz.statistics.table.dal.TableStatDAO;
import com.alibaba.otter.manager.biz.statistics.table.dal.dataobject.TableHistoryStatDO;
import com.alibaba.otter.manager.biz.statistics.table.dal.dataobject.TableStatDO;
import com.alibaba.otter.manager.biz.statistics.table.param.BehaviorHistoryInfo;
import com.alibaba.otter.manager.biz.statistics.table.param.TimelineBehaviorHistoryCondition;
import com.alibaba.otter.shared.common.model.statistics.table.TableStat;
import com.alibaba.otter.shared.common.utils.Assert;
import com.alibaba.otter.shared.common.utils.thread.NamedThreadFactory;

/**
 * @author danping.yudp
 */
public class TableStatServiceImpl implements TableStatService, InitializingBean {

    private static final Logger         logger     = LoggerFactory.getLogger(TableStatServiceImpl.class);
    private TableStatDAO                tableStatDao;
    private TableHistoryStatDAO         tableHistoryStatDao;
    private Map<Long, TableStat>        tableStats = new HashMap<Long, TableStat>();
    private Long                        statUnit   = 60 * 1000L;                                         //?????????????????????60???
    private ScheduledThreadPoolExecutor scheduler;

    /**
     * ?????????pipeLineId???DataMediaPairId??????????????????????????????tableStat???????????????????????????????????????Table??????????????? ?????????????????????????????????
     */
    public void updateTableStat(TableStat stat) {
        Assert.assertNotNull(stat);
        int affect = tableStatDao.modifyTableStat(tableStatModelToDo(stat));
        if (affect == 0) {
            tableStatDao.insertTableStat(tableStatModelToDo(stat));
        }

        if (stat.getStartTime() != null && stat.getEndTime() != null) {
            if (statUnit <= 0) {
                insertBehaviorHistory(stat);
            } else {
                synchronized (tableStats) {
                    // ?????????????????????
                    TableStat old = tableStats.get(stat.getDataMediaPairId());
                    if (old != null) {
                        //????????????
                        old.setInsertCount(stat.getInsertCount() + old.getInsertCount());
                        old.setUpdateCount(stat.getUpdateCount() + old.getUpdateCount());
                        old.setDeleteCount(stat.getDeleteCount() + old.getDeleteCount());
                        old.setFileCount(stat.getFileCount() + old.getFileCount());
                        old.setFileSize(stat.getFileSize() + old.getFileSize());
                        if (stat.getEndTime().after(old.getEndTime())) {
                            old.setEndTime(stat.getEndTime());
                        }
                        if (stat.getStartTime().before(old.getStartTime())) {
                            old.setStartTime(stat.getStartTime());
                        }
                    } else {
                        tableStats.put(stat.getDataMediaPairId(), stat);
                    }
                }
            }
        }
    }

    /**
     * ??????????????????????????????????????????
     */
    public List<TableStat> listTableStat(Long pipelineId) {
        Assert.assertNotNull(pipelineId);
        List<TableStatDO> tableStatDOs = tableStatDao.listTableStatsByPipelineId(pipelineId);
        List<TableStat> tableStats = new ArrayList<TableStat>();
        for (TableStatDO tableStatDO : tableStatDOs) {
            tableStats.add(tableStatDOToModel(tableStatDO));
        }
        return tableStats;
    }

    public void insertBehaviorHistory(TableStat stat) {
        tableHistoryStatDao.insertTableHistoryStat(tableHistoryStatModelToDo(stat));
    }

    /**
     * ??????pairId??????start-end???????????????tableStat, ??????????????????????????????????????????????????????????????????????????????end_time????????????, ??????1???????????????????????????
     */
    public Map<Long, BehaviorHistoryInfo> listTimelineBehaviorHistory(TimelineBehaviorHistoryCondition condition) {

        Assert.assertNotNull(condition);
        Map<Long, BehaviorHistoryInfo> behaviorHistoryInfos = new LinkedHashMap<Long, BehaviorHistoryInfo>();
        List<TableHistoryStatDO> tableHistoryStatDOs = tableHistoryStatDao.listTimelineTableStat(condition);
        int size = tableHistoryStatDOs.size();
        int k = size - 1;
        for (Long i = condition.getStart().getTime(); i <= condition.getEnd().getTime(); i += 60 * 1000) {
            BehaviorHistoryInfo behaviorHistoryInfo = new BehaviorHistoryInfo();
            List<TableStat> tableStat = new ArrayList<TableStat>();
            // ?????????????????????i??????????????????k????????????????????????????????????????????????????????????????????????????????????
            for (int j = k; j >= 0; --j) {
                if ((i - tableHistoryStatDOs.get(j).getEndTime().getTime() <= 60 * 1000)
                    && (i - tableHistoryStatDOs.get(j).getEndTime().getTime() >= 0)) {
                    tableStat.add(tableHistoryStatDOToModel(tableHistoryStatDOs.get(j)));
                    k = j - 1;
                }// ???????????????if?????????????????????????????????????????????
                else {
                    break;
                }
            }
            if (tableStat.size() > 0) {
                behaviorHistoryInfo.setItems(tableStat);
                behaviorHistoryInfos.put(i, behaviorHistoryInfo);
            }

        }
        return behaviorHistoryInfos;
    }

    private void flushBehaviorHistory() {
        synchronized (tableStats) {
            // ????????????????????????delay????????????
            Collection<TableStat> stats = tableStats.values();
            for (TableStat stat : stats) {
                insertBehaviorHistory(stat);
            }
            tableStats.clear();
        }
    }

    public void afterPropertiesSet() throws Exception {
        scheduler = new ScheduledThreadPoolExecutor(1, new NamedThreadFactory("Otter-Statistics-Table"),
                                                    new ThreadPoolExecutor.CallerRunsPolicy());
        if (statUnit > 0) {
            scheduler.scheduleAtFixedRate(new Runnable() {

                public void run() {
                    try {
                        flushBehaviorHistory();
                    } catch (Exception e) {
                        logger.error("flush delay stat failed!", e);
                    }
                }
            }, statUnit, statUnit, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * ??????Model???????????????DO??????
     * 
     * @param tableStat
     * @return TableStatDO
     */
    private TableStatDO tableStatModelToDo(TableStat tableStat) {
        TableStatDO tableStatDO = new TableStatDO();
        tableStatDO.setId(tableStat.getId());
        tableStatDO.setPipelineId(tableStat.getPipelineId());
        tableStatDO.setDataMediaPairId(tableStat.getDataMediaPairId());
        tableStatDO.setFileSize(tableStat.getFileSize());
        tableStatDO.setFileCount(tableStat.getFileCount());
        tableStatDO.setDeleteCount(tableStat.getDeleteCount());
        tableStatDO.setInsertCount(tableStat.getInsertCount());
        tableStatDO.setUpdateCount(tableStat.getUpdateCount());
        tableStatDO.setGmtCreate(tableStat.getGmtCreate());
        tableStatDO.setGmtModified(tableStat.getGmtModified());
        return tableStatDO;

    }

    /**
     * ??????DO???????????????Model??????
     * 
     * @param tableStatDO
     * @return TableStat
     */
    private TableStat tableStatDOToModel(TableStatDO tableStatDO) {
        TableStat tableStat = new TableStat();
        tableStat.setId(tableStatDO.getId());
        tableStat.setPipelineId(tableStatDO.getPipelineId());
        tableStat.setDataMediaPairId(tableStatDO.getDataMediaPairId());
        tableStat.setFileSize(tableStatDO.getFileSize());
        tableStat.setFileCount(tableStatDO.getFileCount());
        tableStat.setDeleteCount(tableStatDO.getDeleteCount());
        tableStat.setInsertCount(tableStatDO.getInsertCount());
        tableStat.setUpdateCount(tableStatDO.getUpdateCount());
        tableStat.setGmtCreate(tableStatDO.getGmtCreate());
        tableStat.setGmtModified(tableStatDO.getGmtModified());
        return tableStat;

    }

    /**
     * ??????Model???????????????DO??????
     * 
     * @param tableStat
     * @return TableHistoryStatDO
     */
    private TableHistoryStatDO tableHistoryStatModelToDo(TableStat tableStat) {
        TableHistoryStatDO tableHistoryStatDO = new TableHistoryStatDO();
        tableHistoryStatDO.setId(tableStat.getId());
        tableHistoryStatDO.setPipelineId(tableStat.getPipelineId());
        tableHistoryStatDO.setDataMediaPairId(tableStat.getDataMediaPairId());
        tableHistoryStatDO.setStartTime(tableStat.getStartTime());
        tableHistoryStatDO.setEndTime(tableStat.getEndTime());
        tableHistoryStatDO.setFileSize(tableStat.getFileSize());
        tableHistoryStatDO.setFileCount(tableStat.getFileCount());
        tableHistoryStatDO.setDeleteCount(tableStat.getDeleteCount());
        tableHistoryStatDO.setInsertCount(tableStat.getInsertCount());
        tableHistoryStatDO.setUpdateCount(tableStat.getUpdateCount());
        tableHistoryStatDO.setGmtCreate(tableStat.getGmtCreate());
        tableHistoryStatDO.setGmtModified(tableStat.getGmtModified());
        return tableHistoryStatDO;

    }

    /**
     * ??????DO???????????????Model??????
     * 
     * @param TableHistoryStatDO
     * @return TableStat
     */
    private TableStat tableHistoryStatDOToModel(TableHistoryStatDO tableHistoryStatDO) {
        TableStat tableStat = new TableStat();
        tableStat.setId(tableHistoryStatDO.getId());
        tableStat.setPipelineId(tableHistoryStatDO.getPipelineId());
        tableStat.setDataMediaPairId(tableHistoryStatDO.getDataMediaPairId());
        tableStat.setStartTime(tableHistoryStatDO.getStartTime());
        tableStat.setEndTime(tableHistoryStatDO.getEndTime());
        tableStat.setFileSize(tableHistoryStatDO.getFileSize());
        tableStat.setFileCount(tableHistoryStatDO.getFileCount());
        tableStat.setDeleteCount(tableHistoryStatDO.getDeleteCount());
        tableStat.setInsertCount(tableHistoryStatDO.getInsertCount());
        tableStat.setUpdateCount(tableHistoryStatDO.getUpdateCount());
        tableStat.setGmtCreate(tableHistoryStatDO.getGmtCreate());
        tableStat.setGmtModified(tableHistoryStatDO.getGmtModified());
        return tableStat;

    }

    public void setTableStatDao(TableStatDAO tableStatDao) {
        this.tableStatDao = tableStatDao;
    }

    public void setTableHistoryStatDao(TableHistoryStatDAO tableHistoryStatDao) {
        this.tableHistoryStatDao = tableHistoryStatDao;
    }

}
