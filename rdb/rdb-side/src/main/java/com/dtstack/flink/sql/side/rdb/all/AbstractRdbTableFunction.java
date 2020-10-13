/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.dtstack.flink.sql.side.rdb.all;

import com.dtstack.flink.sql.side.BaseSideInfo;
import com.dtstack.flink.sql.side.rdb.table.RdbSideTableInfo;
import com.dtstack.flink.sql.side.rdb.util.SwitchUtil;
import com.dtstack.flink.sql.side.table.BaseTableFunction;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.apache.commons.collections.CollectionUtils;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.table.functions.FunctionContext;
import org.apache.flink.table.typeutils.TimeIndicatorTypeInfo;
import org.apache.flink.types.Row;
import org.apache.flink.types.RowKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * @author: chuixue
 * @create: 2020-10-10 18:58
 * @description:Rdb全量维表公共的类
 **/
abstract public class AbstractRdbTableFunction extends BaseTableFunction {
    private static final Logger LOG = LoggerFactory.getLogger(AbstractRdbTableFunction.class);

    private static final int CONN_RETRY_NUM = 3;

    private static final int DEFAULT_FETCH_SIZE = 1000;

    private AtomicReference<Map<String, List<Map<String, Object>>>> cacheRef = new AtomicReference<>();

    public AbstractRdbTableFunction(BaseSideInfo sideInfo) {
        super(sideInfo);
    }

    @Override
    public void open(FunctionContext context) throws Exception {
        super.open(context);
        RdbSideTableInfo tableInfo = (RdbSideTableInfo) sideInfo.getSideTableInfo();
        LOG.info("rdb dim table config info: {} ", tableInfo.toString());
    }

    @Override
    protected void initCache() throws SQLException {
        Map<String, List<Map<String, Object>>> newCache = Maps.newConcurrentMap();
        cacheRef.set(newCache);
        loadData(newCache);
    }

    @Override
    protected void reloadCache() {
        //reload cacheRef and replace to old cacheRef
        Map<String, List<Map<String, Object>>> newCache = Maps.newConcurrentMap();
        try {
            loadData(newCache);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        cacheRef.set(newCache);
        LOG.info("----- rdb all cacheRef reload end:{}", Calendar.getInstance());
    }

    /**
     * 每条数据都会进入该方法
     *
     * @param keys 维表join key的值
     */
    public void eval(Object... keys) {
        String cacheKey = Arrays.stream(keys)
                .map(Object::toString)
                .collect(Collectors.joining("_"));
        List<Map<String, Object>> cacheList = cacheRef.get().get(cacheKey);
        // 有数据才往下发，(左/内)连接flink会做相应的处理
        if (!CollectionUtils.isEmpty(cacheList)) {
            cacheList.stream().forEach(one -> collect(fillData(one)));
        }
    }

    @Override
    public Row fillData(Object sideInput) {
        Map<String, Object> cacheInfo = (Map<String, Object>) sideInput;
        Collection<String> fields = sideInfo.getSideTableInfo().getPhysicalFields().values();
        String[] fieldsArr = fields.toArray(new String[fields.size()]);
        Row row = new Row(fieldsArr.length);
        for (int i = 0; i < fieldsArr.length; i++) {
            row.setField(i, cacheInfo.get(fieldsArr[i]));
        }
        row.setKind(RowKind.INSERT);
        return row;
    }

    @Override
    public Row fillData(Row input, Object sideInput) {
        return null;
    }

    /**
     * covert flink time attribute.Type information for indicating event or processing time.
     * However, it behaves like a regular SQL timestamp but is serialized as Long.
     *
     * @param entry
     * @param obj
     * @return
     */
    protected Object dealTimeAttributeType(Class<? extends TypeInformation> entry, Object obj) {
        boolean isTimeIndicatorTypeInfo = TimeIndicatorTypeInfo.class.isAssignableFrom(entry);
        if (obj instanceof LocalDateTime && isTimeIndicatorTypeInfo) {
            //去除上一层OutputRowtimeProcessFunction 调用时区导致的影响
            obj = ((Timestamp) obj).getTime() + (long) LOCAL_TZ.getOffset(((Timestamp) obj).getTime());
        }
        return obj;
    }

    private void loadData(Map<String, List<Map<String, Object>>> tmpCache) throws SQLException {
        RdbSideTableInfo tableInfo = (RdbSideTableInfo) sideInfo.getSideTableInfo();
        Connection connection = null;

        try {
            for (int i = 0; i < CONN_RETRY_NUM; i++) {
                try {
                    connection = getConn(tableInfo.getUrl(), tableInfo.getUserName(), tableInfo.getPassword());
                    break;
                } catch (Exception e) {
                    if (i == CONN_RETRY_NUM - 1) {
                        throw new RuntimeException("", e);
                    }
                    try {
                        String connInfo = "url:" + tableInfo.getUrl() + ";userName:" + tableInfo.getUserName() + ",pwd:" + tableInfo.getPassword();
                        LOG.warn("get conn fail, wait for 5 sec and try again, connInfo:" + connInfo);
                        Thread.sleep(5 * 1000);
                    } catch (InterruptedException e1) {
                        LOG.error("", e1);
                    }
                }
            }
            queryAndFillData(tmpCache, connection);
        } catch (Exception e) {
            LOG.error("", e);
            throw new SQLException(e);
        } finally {
            if (connection != null) {
                connection.close();
            }
        }
    }

    private void queryAndFillData(Map<String, List<Map<String, Object>>> tmpCache, Connection connection) throws SQLException {
        //load data from table
        String sql = sideInfo.getFlinkPlannerSqlCondition();
        Statement statement = connection.createStatement();
        statement.setFetchSize(getFetchSize());
        ResultSet resultSet = statement.executeQuery(sql);

        String[] sideFieldNames = sideInfo.getSideTableInfo().getPhysicalFields().values().stream().toArray(String[]::new);
        String[] fields = sideInfo.getSideTableInfo().getFieldTypes();
        while (resultSet.next()) {
            Map<String, Object> oneRow = Maps.newHashMap();
            for (int i = 0; i < sideFieldNames.length; i++) {
                Object object = resultSet.getObject(sideFieldNames[i].trim());
                object = SwitchUtil.getTarget(object, fields[i]);
                oneRow.put(sideFieldNames[i].trim(), object);
            }

            // 拿到维表字段的物理类型
            String[] lookupKeys = sideInfo.getLookupKeys();
            List<String> physicalFields = Arrays.stream(lookupKeys)
                    .map(sideInfo.getSideTableInfo().getPhysicalFields()::get)
                    .collect(Collectors.toList());

            String cacheKey = physicalFields.stream()
                    .map(oneRow::get)
                    .map(Object::toString)
                    .collect(Collectors.joining("_"));

            tmpCache.computeIfAbsent(cacheKey, key -> Lists.newArrayList())
                    .add(oneRow);
        }
    }

    public int getFetchSize() {
        return DEFAULT_FETCH_SIZE;
    }

    /**
     * get jdbc connection
     *
     * @param dbUrl
     * @param userName
     * @param password
     * @return
     */
    public abstract Connection getConn(String dbUrl, String userName, String password);
}
