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

package com.dtstack.flinkx.connector.es.source;

import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.table.data.RowData;
import org.apache.flink.table.types.logical.RowType;

import com.dtstack.flinkx.conf.SyncConf;
import com.dtstack.flinkx.connector.es.conf.EsConf;
import com.dtstack.flinkx.connector.es.converter.EsColumnConverter;
import com.dtstack.flinkx.connector.es.converter.EsRawTypeConverter;
import com.dtstack.flinkx.converter.RawTypeConverter;
import com.dtstack.flinkx.source.SourceFactory;
import com.dtstack.flinkx.util.JsonUtil;
import com.dtstack.flinkx.util.TableUtil;

/**
 * @description:
 * @program: flinkx-all
 * @author: lany
 * @create: 2021/06/18 12:01
 */
public class EsSourceFactory extends SourceFactory {

    private final EsConf elasticsearchConf;

    public EsSourceFactory(SyncConf syncConf, StreamExecutionEnvironment env) {
        super(syncConf, env);
        elasticsearchConf =
                JsonUtil.toObject(
                        JsonUtil.toJson(syncConf.getReader().getParameter()), EsConf.class);
        elasticsearchConf.setColumn(syncConf.getReader().getFieldList());
        super.initFlinkxCommonConf(elasticsearchConf);
        elasticsearchConf.setParallelism(1);
    }

    @Override
    public DataStream<RowData> createSource() {
        EsInputFormatBuilder builder = new EsInputFormatBuilder();
        builder.setEsConf(elasticsearchConf);
        final RowType rowType = TableUtil.createRowType(
                elasticsearchConf.getColumn(),
                getRawTypeConverter());
        builder.setRowConverter(new EsColumnConverter(rowType));
        return createInput(builder.finish());
    }

    @Override
    public RawTypeConverter getRawTypeConverter() {
        return EsRawTypeConverter::apply;
    }
}
