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

package com.dtstack.flinkx.connector.jdbc.source;


import org.apache.flink.core.io.InputSplit;
import org.apache.flink.table.data.GenericRowData;
import org.apache.flink.table.data.RowData;

import com.dtstack.flinkx.conf.FieldConf;
import com.dtstack.flinkx.connector.jdbc.JdbcDialect;
import com.dtstack.flinkx.connector.jdbc.conf.JdbcConf;
import com.dtstack.flinkx.connector.jdbc.util.JdbcUtil;
import com.dtstack.flinkx.constants.ConstantValue;
import com.dtstack.flinkx.constants.Metrics;
import com.dtstack.flinkx.element.ColumnRowData;
import com.dtstack.flinkx.enums.ColumnType;
import com.dtstack.flinkx.inputformat.BaseRichInputFormat;
import com.dtstack.flinkx.metrics.BigIntegerAccmulator;
import com.dtstack.flinkx.metrics.StringAccumulator;
import com.dtstack.flinkx.restore.FormatState;
import com.dtstack.flinkx.throwable.FlinkxRuntimeException;
import com.dtstack.flinkx.util.ExceptionUtil;
import com.dtstack.flinkx.util.GsonUtil;
import com.dtstack.flinkx.util.StringUtil;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;

import java.io.IOException;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * InputFormat for reading data from a database and generate Rows.
 * <p>
 * Company: www.dtstack.com
 *
 * @author huyifan.zju@163.com
 */
public class JdbcInputFormat extends BaseRichInputFormat {
    public static final long serialVersionUID = 1L;
    protected static final int resultSetConcurrency = ResultSet.CONCUR_READ_ONLY;
    protected static int resultSetType = ResultSet.TYPE_FORWARD_ONLY;

    protected JdbcConf jdbcConf;
    protected int numPartitions = 1;
    protected JdbcDialect jdbcDialect;

    protected transient Connection dbConn;
    protected transient Statement statement;
    protected transient PreparedStatement ps;
    protected transient ResultSet resultSet;
    protected boolean hasNext;

    protected int columnCount;
    protected RowData lastRow = null;

    protected StringAccumulator maxValueAccumulator;
    protected BigIntegerAccmulator endLocationAccumulator;
    protected BigIntegerAccmulator startLocationAccumulator;

    //轮询增量标识字段类型
    protected ColumnType type;

    @Override
    public void openInternal(InputSplit inputSplit) {
        initMetric(inputSplit);
        if (!canReadData(inputSplit)) {
            LOG.warn("Not read data when the start location are equal to end location");
            hasNext = false;
            return;
        }
        try {
            dbConn = getConnection();
            dbConn.setAutoCommit(false);
            initColumnList();
            jdbcConf.setQuerySql(buildQuerySql(inputSplit));
            executeQuery(((JdbcInputSplit) inputSplit).getStartLocation());
            if (!resultSet.isClosed()) {
                columnCount = resultSet.getMetaData().getColumnCount();
            }
        } catch (SQLException se) {
            throw new IllegalArgumentException("open() failed." + se.getMessage(), se);
        }
    }

    @Override
    public InputSplit[] createInputSplitsInternal(int minNumSplits) {
        JdbcInputSplit[] splits = new JdbcInputSplit[minNumSplits];
        for (int i = 0; i < minNumSplits; i++) {
            splits[i] = new JdbcInputSplit(i, numPartitions, i, jdbcConf.getStartLocation(), null);
        }
        return splits;
    }

    @Override
    public boolean reachedEnd() {
        if (hasNext) {
            return false;
        } else {
            if (jdbcConf.isPolling()) {
                try {
                    TimeUnit.MILLISECONDS.sleep(jdbcConf.getPollingInterval());
                    //间隔轮询检测数据库连接是否断开，超时时间三秒，断开后自动重连
                    if(!dbConn.isValid(3)){
                        dbConn = getConnection();
                        //重新连接后还是不可用则认为数据库异常，任务失败
                        if(!dbConn.isValid(3)){
                            String message = String.format("cannot connect to %s, username = %s, please check %s is available.", jdbcConf.getJdbcUrl(), jdbcConf.getJdbcUrl(), jdbcDialect.dialectName());
                            throw new RuntimeException(message);
                        }
                    }
                    if (!dbConn.getAutoCommit()) {
                        dbConn.setAutoCommit(true);
                    }
                    JdbcUtil.closeDbResources(resultSet, null, null, false);
                    //此处endLocation理应不会为空
                    queryForPolling(endLocationAccumulator.getLocalValue().toString());
                    return false;
                } catch (InterruptedException e) {
                    LOG.warn("interrupted while waiting for polling, e = {}", ExceptionUtil.getErrorMessage(e));
                } catch (SQLException e) {
                    JdbcUtil.closeDbResources(resultSet, ps, null, false);
                    String message = String.format("error to execute sql = %s, startLocation = %s, e = %s",
                            jdbcConf.getQuerySql(),
                            endLocationAccumulator.getLocalValue(),
                            ExceptionUtil.getErrorMessage(e));
                    throw new RuntimeException(message, e);
                }
            }
            return true;
        }
    }

    @Override
    public RowData nextRecordInternal(RowData rowData) throws IOException {
        if (!hasNext) {
            return null;
        }
        try {
            // todo 抽到DB2插件里面
//            updateColumnCount();
            @SuppressWarnings("unchecked")
            RowData rawRowData = rowConverter.toInternal(resultSet);
            RowData finalRowData = loadConstantData(rawRowData, jdbcConf.getColumn());

            boolean isUpdateLocation = jdbcConf.isPolling() || (jdbcConf.isIncrement() && !jdbcConf.isUseMaxFunc());
            if (isUpdateLocation) {
                String location = null;
                Object obj = resultSet.getObject(jdbcConf.getIncreColumn());
                if (obj != null) {
                    boolean isTimestampType = obj.getClass().getSimpleName().toUpperCase().contains(ColumnType.TIMESTAMP.name());
                    if (obj instanceof java.util.Date || isTimestampType) {
                        obj = resultSet.getTimestamp(jdbcConf.getIncreColumn()).getTime();
                    }
                    location = String.valueOf(obj);
                    endLocationAccumulator.add(new BigInteger(location));
                }

                LOG.debug("update endLocationAccumulator, current Location = {}", location);
            }

            hasNext = resultSet.next();
            lastRow = finalRowData;
            return finalRowData;
        } catch (SQLException se) {
            throw new IOException("Couldn't read data - " + se.getMessage(), se);
        } catch (Exception npe) {
            throw new IOException("Couldn't access resultSet", npe);
        }
    }


    @Override
    public FormatState getFormatState() {
        super.getFormatState();

        if (formatState != null && lastRow != null && jdbcConf.getRestoreColumnIndex() > -1) {
            Object state;
            if (lastRow instanceof GenericRowData) {
                state = ((GenericRowData) lastRow).getField(jdbcConf.getRestoreColumnIndex());
            } else if (lastRow instanceof ColumnRowData) {
                state = ((ColumnRowData) lastRow).getField(jdbcConf.getRestoreColumnIndex());
            } else {
                throw new RuntimeException("not support RowData:" + lastRow.getClass());
            }
            formatState.setState(state);
        }
        return formatState;
    }

    @Override
    public void closeInternal() {
        JdbcUtil.closeDbResources(resultSet, statement, dbConn, true);
    }

    /**
     * 初始化增量或或间隔轮询任务累加器
     *
     * @param inputSplit 数据分片
     */
    protected void initMetric(InputSplit inputSplit) {
        if (!jdbcConf.isIncrement()) {
            return;
        }
        //初始化增量、轮询字段类型
        type = ColumnType.fromString(jdbcConf.getIncreColumnType());
        startLocationAccumulator = new BigIntegerAccmulator();
        endLocationAccumulator = new BigIntegerAccmulator();
        String startLocation = StringUtil.stringToTimestampStr(jdbcConf.getStartLocation(), type);

        if (StringUtils.isNotEmpty(jdbcConf.getStartLocation())) {
            ((JdbcInputSplit) inputSplit).setStartLocation(startLocation);
            startLocationAccumulator.add(new BigInteger(startLocation));
        }

        //轮询任务endLocation设置为startLocation的值
        if (jdbcConf.isPolling()) {
            if (StringUtils.isNotEmpty(startLocation)) {
                endLocationAccumulator.add(new BigInteger(startLocation));
            }
        } else if (jdbcConf.isUseMaxFunc()) {
            //如果不是轮询任务，则只能是增量任务，若useMaxFunc设置为true，则去数据库查询当前增量字段的最大值
            getMaxValue(inputSplit);
            //endLocation设置为数据库中查询的最大值
            String endLocation = ((JdbcInputSplit) inputSplit).getEndLocation();
            endLocationAccumulator.add(new BigInteger(StringUtil.stringToTimestampStr(endLocation, type)));
        } else {
            //增量任务，且useMaxFunc设置为false，如果startLocation不为空，则将endLocation初始值设置为startLocation的值，防止数据库无增量数据时下次获取到的startLocation为空
            if (StringUtils.isNotEmpty(startLocation)) {
                endLocationAccumulator.add(new BigInteger(startLocation));
            }
        }

        //将累加器信息添加至prometheus
        customPrometheusReporter.registerMetric(startLocationAccumulator, Metrics.START_LOCATION);
        customPrometheusReporter.registerMetric(endLocationAccumulator, Metrics.END_LOCATION);
        getRuntimeContext().addAccumulator(Metrics.START_LOCATION, startLocationAccumulator);
        getRuntimeContext().addAccumulator(Metrics.END_LOCATION, endLocationAccumulator);
    }

    /**
     * 将增量任务的数据最大值设置到累加器中
     *
     * @param inputSplit 数据分片
     */
    protected void getMaxValue(InputSplit inputSplit) {
        String maxValue;
        if (inputSplit.getSplitNumber() == 0) {
            maxValue = getMaxValueFromDb();
            //将累加器信息上传至flink，供其他通道通过flink rest api获取该最大值
            maxValueAccumulator = new StringAccumulator();
            maxValueAccumulator.add(maxValue);
            getRuntimeContext().addAccumulator(Metrics.MAX_VALUE, maxValueAccumulator);
        } else {
            maxValue = String.valueOf(accumulatorCollector.getAccumulatorValue(Metrics.MAX_VALUE, true));
        }

        ((JdbcInputSplit) inputSplit).setEndLocation(maxValue);
    }

    /**
     * 从数据库中查询增量字段的最大值
     *
     * @return
     */
    private String getMaxValueFromDb() {
        String maxValue = null;
        Connection conn = null;
        Statement st = null;
        ResultSet rs = null;
        try {
            long startTime = System.currentTimeMillis();

            String queryMaxValueSql;
            if (StringUtils.isNotEmpty(jdbcConf.getCustomSql())) {
                queryMaxValueSql = String.format("select max(%s.%s) as max_value from ( %s ) %s",
                        JdbcUtil.TEMPORARY_TABLE_NAME,
                        jdbcDialect.quoteIdentifier(jdbcConf.getIncreColumn()),
                        jdbcConf.getCustomSql(),
                        JdbcUtil.TEMPORARY_TABLE_NAME);
            } else {
                queryMaxValueSql = String.format("select max(%s) as max_value from %s",
                        jdbcDialect.quoteIdentifier(jdbcConf.getIncreColumn()),
                        jdbcDialect.quoteIdentifier(jdbcConf.getTable()));
            }

            String startSql = buildStartLocationSql(
                    jdbcConf.getIncreColumnType(),
                    jdbcDialect.quoteIdentifier(jdbcConf.getIncreColumn()),
                    jdbcConf.getStartLocation(),
                    jdbcConf.isUseMaxFunc());
            if (StringUtils.isNotEmpty(startSql)) {
                queryMaxValueSql += " where " + startSql;
            }

            LOG.info(String.format("Query max value sql is '%s'", queryMaxValueSql));

            conn = getConnection();
            st = conn.createStatement(resultSetType, resultSetConcurrency);
            st.setQueryTimeout(jdbcConf.getQueryTimeOut());
            rs = st.executeQuery(queryMaxValueSql);
            if (rs.next()) {
                switch (type) {
                    case TIMESTAMP:
                        maxValue = String.valueOf(rs.getTimestamp("max_value").getTime());
                        break;
                    case DATE:
                        maxValue = String.valueOf(rs.getDate("max_value").getTime());
                        break;
                    default:
                        maxValue = StringUtil.stringToTimestampStr(String.valueOf(rs.getObject("max_value")), type);
                }
            }

            LOG.info(String.format("Takes [%s] milliseconds to get the maximum value [%s]", System.currentTimeMillis() - startTime, maxValue));

            return maxValue;
        } catch (Throwable e) {
            throw new RuntimeException("Get max value from " + jdbcConf.getTable() + " error", e);
        } finally {
            JdbcUtil.closeDbResources(rs, st, conn, false);
        }
    }

    /**
     * 判断增量任务是否还能继续读取数据
     * 增量任务，startLocation = endLocation且两者都不为null，返回false，其余情况返回true
     *
     * @param split 数据分片
     *
     * @return
     */
    protected boolean canReadData(InputSplit split) {
        //只排除增量同步
        if (!jdbcConf.isIncrement() || jdbcConf.isPolling()) {
            return true;
        }

        JdbcInputSplit jdbcInputSplit = (JdbcInputSplit) split;
        if (jdbcInputSplit.getStartLocation() == null && jdbcInputSplit.getEndLocation() == null) {
            return true;
        }

        return !StringUtils.equals(jdbcInputSplit.getStartLocation(), jdbcInputSplit.getEndLocation());
    }

    /**
     * 构造查询sql
     *
     * @param inputSplit 数据切片
     *
     * @return 构建的sql字符串
     */
    protected String buildQuerySql(InputSplit inputSplit) {
        List<String> whereList = new ArrayList<>();

        //1、TODO 分片SQL，先跳过

        if (inputSplit != null) {
            JdbcInputSplit jdbcInputSplit = (JdbcInputSplit) inputSplit;
            String startLocation = jdbcInputSplit.getStartLocation();
            if (formatState.getState() != null && StringUtils.isNotBlank(jdbcConf.getRestoreColumn())) {
                startLocation = String.valueOf(formatState.getState());
                if (StringUtils.isNotBlank(startLocation)) {
                    if(endLocationAccumulator != null){
                        endLocationAccumulator.add(new BigInteger(startLocation));
                    }
                    LOG.info("restore from checkpoint, update startLocation, before = {}, after = {}", startLocation, startLocation);
                    jdbcInputSplit.setStartLocation(startLocation);
                    StringBuilder sql = new StringBuilder(64);
                    sql.append(buildFilterSql(">", startLocation, jdbcConf.getRestoreColumn(), jdbcConf.getRestoreColumnType(), jdbcConf.isPolling()));
                    whereList.add(sql.toString());
                }
            } else if (jdbcConf.isIncrement()) {
                if (StringUtils.isNotBlank(startLocation)) {
                    StringBuilder sql = new StringBuilder(64);
                    String operator = jdbcConf.isUseMaxFunc() ? " >= " : " > ";
                    sql.append(buildFilterSql(operator, startLocation, jdbcConf.getIncreColumn(), jdbcConf.getIncreColumnType(), jdbcConf.isPolling()));
                    whereList.add(sql.toString());
                }
                if (StringUtils.isNotBlank(jdbcInputSplit.getEndLocation())) {
                    StringBuilder sql = new StringBuilder(64);
                    sql.append(buildFilterSql("<", jdbcInputSplit.getEndLocation(), jdbcConf.getIncreColumn(), jdbcConf.getIncreColumnType(), false));
                    whereList.add(sql.toString());
                }
            }
        }
        if (StringUtils.isNotBlank(jdbcConf.getWhere())) {
            whereList.add(jdbcConf.getWhere());
        }

        StringBuilder sql = new StringBuilder(128);
        sql.append(String.join(" AND ", whereList.toArray(new String[0])));

        if ((Objects.nonNull(jdbcConf.getParallelism()) && jdbcConf.getParallelism() > 1)
                && StringUtils.isNotBlank(jdbcConf.getSplitPk())) {
            sql.append(" ORDER BY ")
                    .append(jdbcDialect.quoteIdentifier(jdbcConf.getSplitPk()))
                    .append(" ASC");
        }

        String querySql = jdbcDialect.getSelectFromStatement(jdbcConf.getSchema(), jdbcConf.getTable(), jdbcConf.getCustomSql(), columnNameList.toArray(new String[0]), sql.toString());

        LOG.warn("Executing sql is: '{}'", querySql);
        return querySql;
    }

    /**
     * 构造过滤条件SQL
     *
     * @param operator 比较符
     * @param location 比较的值
     * @param columnName 字段名称
     * @param columnType 字段类型
     * @param isPolling 是否是轮询任务
     *
     * @return
     */
    public String buildFilterSql(String operator, String location, String columnName, String columnType, boolean isPolling) {
        StringBuilder sql = new StringBuilder(64);
        if (StringUtils.isNotEmpty(jdbcConf.getCustomSql())) {
            sql.append(JdbcUtil.TEMPORARY_TABLE_NAME).append(".");
        }
        sql.append(jdbcDialect.quoteIdentifier(columnName))
                .append(" ")
                .append(operator)
                .append(" ");
        if (isPolling) {
            //轮询任务使用占位符
            sql.append("?");
        } else {
            sql.append(buildLocation(columnType, location));
        }
        return sql.toString();
    }

    /**
     * buildLocation
     *
     * @param columnType
     * @param location
     *
     * @return
     */
    public String buildLocation(String columnType, String location) {
        if (ColumnType.isTimeType(columnType)) {
            return getTimeStr(Long.parseLong(location));
        } else if (ColumnType.isNumberType(columnType)) {
            return location;
        } else {
            return "'" + location + "'";
        }
    }

    /**
     * 构建起始位置sql
     *
     * @param incrementColType 增量字段类型
     * @param incrementCol 增量字段名称
     * @param startLocation 开始位置
     * @param useMaxFunc 是否保存结束位置数据
     *
     * @return
     */
    protected String buildStartLocationSql(String incrementColType, String incrementCol, String startLocation, boolean useMaxFunc) {
        if (org.apache.commons.lang.StringUtils.isEmpty(startLocation)
                || JdbcUtil.NULL_STRING.equalsIgnoreCase(startLocation)) {
            return null;
        }

        String operator = useMaxFunc ? " >= " : " > ";

        //增量轮询，startLocation使用占位符代替
        if (jdbcConf.isPolling()) {
            return incrementCol + operator + "?";
        }

        return getLocationSql(incrementColType, incrementCol, startLocation, operator);
    }

    /**
     * 构建边界位置sql
     *
     * @param incrementColType 增量字段类型
     * @param incrementCol 增量字段名称
     * @param location 边界位置(起始/结束)
     * @param operator 判断符( >, >=,  <)
     *
     * @return
     */
    protected String getLocationSql(String incrementColType, String incrementCol, String location, String operator) {
        String endTimeStr;
        String endLocationSql;

        if (ColumnType.isTimeType(incrementColType)) {
            endTimeStr = getTimeStr(Long.parseLong(location));
            endLocationSql = incrementCol + operator + endTimeStr;
        } else if (ColumnType.isNumberType(incrementColType)) {
            endLocationSql = incrementCol + operator + location;
        } else {
            endTimeStr = String.format("'%s'", location);
            endLocationSql = incrementCol + operator + endTimeStr;
        }

        return endLocationSql;
    }

    /**
     * 构建时间边界字符串
     *
     * @param location 边界位置(起始/结束)
     * @return
     */
    protected String getTimeStr(Long location) {
        String timeStr;
        Timestamp ts = new Timestamp(JdbcUtil.getMillis(location));
        ts.setNanos(JdbcUtil.getNanos(location));
        timeStr = JdbcUtil.getNanosTimeStr(ts.toString());
        timeStr = timeStr.substring(0, 26);
        timeStr = String.format("'%s'", timeStr);

        return timeStr;
    }

    /**
     * 增量轮询查询
     *
     * @param startLocation
     *
     * @throws SQLException
     */
    protected void queryForPolling(String startLocation) throws SQLException {
        LOG.debug("polling startLocation = {}", startLocation);
        boolean isNumber = StringUtils.isNumeric(startLocation);
        switch (type) {
            case TIMESTAMP:
                Timestamp ts = isNumber ? new Timestamp(Long.parseLong(startLocation)) : Timestamp.valueOf(startLocation);
                ps.setTimestamp(1, ts);
                break;
            case DATE:
                Date date = isNumber ? new Date(Long.parseLong(startLocation)) : Date.valueOf(startLocation);
                ps.setDate(1, date);
                break;
            default:
                if (isNumber) {
                    ps.setLong(1, Long.parseLong(startLocation));
                } else {
                    ps.setString(1, startLocation);
                }
        }
        resultSet = ps.executeQuery();
        hasNext = resultSet.next();
    }

    /**
     * 执行查询
     *
     * @param startLocation
     *
     * @throws SQLException
     */
    protected void executeQuery(String startLocation) throws SQLException {
        if (jdbcConf.isPolling()) {
            if (StringUtils.isBlank(startLocation)) {
                //从数据库中获取起始位置
                queryStartLocation();
            } else {
                ps = dbConn.prepareStatement(jdbcConf.getQuerySql(), resultSetType, resultSetConcurrency);
                ps.setFetchSize(jdbcConf.getFetchSize());
                ps.setQueryTimeout(jdbcConf.getQueryTimeOut());
                queryForPolling(startLocation);
            }
        } else {
            statement = dbConn.createStatement(resultSetType, resultSetConcurrency);
            statement.setFetchSize(jdbcConf.getFetchSize());
            statement.setQueryTimeout(jdbcConf.getQueryTimeOut());
            resultSet = statement.executeQuery(jdbcConf.getQuerySql());
            hasNext = resultSet.next();
        }
    }

    /**
     * init columnNameList、 columnTypeList and hasConstantField
     */
    protected void initColumnList() {
        Pair<List<String>, List<String>> pair = getTableMetaData();

        List<FieldConf> fieldList = jdbcConf.getColumn();
        List<String> fullColumnList = pair.getLeft();
        List<String> fullColumnTypeList = pair.getRight();
        handleColumnList(fieldList, fullColumnList, fullColumnTypeList);
    }

    /**
     * for override. because some databases have case-sensitive metadata。
     * @return
     */
    protected Pair<List<String>, List<String>> getTableMetaData() {
        return JdbcUtil.getTableMetaData(jdbcConf.getSchema(), jdbcConf.getTable(), dbConn);
    }

    /**
     * detailed logic for handling column
     * @param fieldList
     * @param fullColumnList
     * @param fullColumnTypeList
     */
    protected void handleColumnList(List<FieldConf> fieldList, List<String> fullColumnList, List<String> fullColumnTypeList) {
        if(fieldList.size() == 1 && StringUtils.equals(ConstantValue.STAR_SYMBOL, fieldList.get(0).getName())){
            columnNameList = fullColumnList;
            columnTypeList = fullColumnTypeList;
            return;
        }

        columnNameList = new ArrayList<>(fieldList.size());
        columnTypeList = new ArrayList<>(fieldList.size());

        for (FieldConf fieldConf : jdbcConf.getColumn()) {
            if(fieldConf.getValue() == null){
                boolean find = false;
                String name = fieldConf.getName();
                for (int i = 0; i <fullColumnList.size(); i++) {
                    if(name.equalsIgnoreCase(fullColumnList.get(i))){
                        columnNameList.add(name);
                        columnTypeList.add(fullColumnTypeList.get(i));
                        find = true;
                        break;
                    }
                }
                if(!find){
                    throw new FlinkxRuntimeException(
                            String.format(
                                    "can not find field:[%s] in columnNameList:[%s]",
                                    name, GsonUtil.GSON.toJson(fullColumnList)));
                }
            }else{
                super.hasConstantField = true;
            }
        }
    }

    /**
     * 间隔轮询查询起始位置
     *
     * @throws SQLException
     */
    private void queryStartLocation() throws SQLException {
        StringBuilder builder = new StringBuilder(128);
        builder.append(jdbcConf.getQuerySql())
                .append(" ORDER BY ")
                .append(jdbcDialect.quoteIdentifier(jdbcConf.getIncreColumn()))
                .append(" ASC");
        ps = dbConn.prepareStatement(builder.toString(), resultSetType, resultSetConcurrency);
        ps.setFetchSize(jdbcConf.getFetchSize());
        //第一次查询数据库中增量字段的最大值
        ps.setFetchDirection(ResultSet.FETCH_REVERSE);
        ps.setQueryTimeout(jdbcConf.getQueryTimeOut());
        resultSet = ps.executeQuery();
        hasNext = resultSet.next();

        try {
            //间隔轮询一直循环，直到查询到数据库中的数据为止
            while (!hasNext) {
                TimeUnit.MILLISECONDS.sleep(jdbcConf.getPollingInterval());
                //执行到此处代表轮询任务startLocation为空，且数据库中无数据，此时需要查询增量字段的最小值
                ps.setFetchDirection(ResultSet.FETCH_FORWARD);
                resultSet.close();
                //如果事务不提交 就会导致数据库即使插入数据 也无法读到数据
                dbConn.commit();
                resultSet = ps.executeQuery();
                hasNext = resultSet.next();
                //每隔五分钟打印一次，(当前时间 - 任务开始时间) % 300秒 <= 一个间隔轮询周期
                if ((System.currentTimeMillis() - startTime) % 300000 <= jdbcConf.getPollingInterval()) {
                    LOG.info("no record matched condition in database, execute query sql = {}, startLocation = {}", jdbcConf.getQuerySql(), endLocationAccumulator.getLocalValue());
                }
            }
        } catch (InterruptedException e) {
            LOG.warn("interrupted while waiting for polling, e = {}", ExceptionUtil.getErrorMessage(e));
        }

        //查询到数据，更新querySql
        builder = new StringBuilder(128);
        builder.append(jdbcConf.getQuerySql())
                .append(" AND ")
                .append(jdbcDialect.quoteIdentifier(jdbcConf.getIncreColumn()))
                .append(" > ? ORDER BY ")
                .append(jdbcDialect.quoteIdentifier(jdbcConf.getIncreColumn()))
                .append(" ASC");
        jdbcConf.setQuerySql(builder.toString());
        ps = dbConn.prepareStatement(jdbcConf.getQuerySql(), resultSetType, resultSetConcurrency);
        ps.setFetchDirection(ResultSet.FETCH_REVERSE);
        ps.setFetchSize(jdbcConf.getFetchSize());
        ps.setQueryTimeout(jdbcConf.getQueryTimeOut());
        LOG.info("update querySql, sql = {}", jdbcConf.getQuerySql());
    }

    /**
     * 获取数据库连接，用于子类覆盖
     *
     * @return connection
     */
    protected Connection getConnection() {
        return JdbcUtil.getConnection(jdbcConf, jdbcDialect);
    }

    /**
     * 使用自定义的指标输出器把增量指标打到普罗米修斯
     */
    @Override
    protected boolean useCustomPrometheusReporter() {
        return jdbcConf.isIncrement();
    }

    /**
     * 为了保证增量数据的准确性，指标输出失败时使任务失败
     */
    @Override
    protected boolean makeTaskFailedWhenReportFailed() {
        return true;
    }

    /**
     * 兼容db2 在间隔轮训场景 且第一次读取时没有任何数据
     * 在openInternal方法调用时 由于数据库没有数据，db2会自动关闭resultSet，因此只有在间隔轮训中某次读取到数据之后，进行更新columnCount
     *
     * @throws SQLException
     */
    private void updateColumnCount() throws SQLException {
        if (columnCount == 0) {
            columnCount = resultSet.getMetaData().getColumnCount();
            boolean splitWithRowCol = numPartitions > 1
                    && StringUtils.isNotEmpty(jdbcConf.getSplitPk())
                    && jdbcConf.getSplitPk().contains("(");
            if (splitWithRowCol) {
                columnCount = columnCount - 1;
            }
        }
    }

    public JdbcConf getJdbcConf() {
        return jdbcConf;
    }

    public void setJdbcConf(JdbcConf jdbcConf) {
        this.jdbcConf = jdbcConf;
    }

    public int getNumPartitions() {
        return numPartitions;
    }

    public void setNumPartitions(int numPartitions) {
        this.numPartitions = numPartitions;
    }

    public JdbcDialect getJdbcDialect() {
        return jdbcDialect;
    }

    public void setJdbcDialect(JdbcDialect jdbcDialect) {
        this.jdbcDialect = jdbcDialect;
    }
}
