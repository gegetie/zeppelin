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

package org.apache.zeppelin.flink;

import org.apache.commons.lang.StringUtils;
import org.apache.flink.table.api.config.ExecutionConfigOptions;
import org.apache.zeppelin.flink.sql.RetractStreamSqlJob;
import org.apache.zeppelin.flink.sql.SingleRowStreamSqlJob;
import org.apache.zeppelin.flink.sql.TimeSeriesStreamSqlJob;
import org.apache.zeppelin.interpreter.Interpreter;
import org.apache.zeppelin.interpreter.InterpreterContext;
import org.apache.zeppelin.interpreter.InterpreterException;
import org.apache.zeppelin.scheduler.Scheduler;
import org.apache.zeppelin.scheduler.SchedulerFactory;

import java.io.IOException;
import java.util.Properties;

public class FlinkStreamSqlInterpreter extends FlinkSqlInterrpeter {

  public FlinkStreamSqlInterpreter(Properties properties) {
    super(properties);
  }

  @Override
  protected boolean isBatch() {
    return false;
  }

  @Override
  public void open() throws InterpreterException {
    super.open();
    this.tbenv = flinkInterpreter.getJavaStreamTableEnvironment();
  }

  @Override
  public void close() throws InterpreterException {

  }

  @Override
  public void callSelect(String sql, InterpreterContext context) throws IOException {
    String savepointDir = context.getLocalProperties().get("savepointDir");
    if (!StringUtils.isBlank(savepointDir)) {
      Object savepointPath = flinkInterpreter.getZeppelinContext()
              .angular(context.getParagraphId() + "_savepointpath", context.getNoteId(), null);
      if (savepointPath == null) {
        LOGGER.info("savepointPath is null because it is the first run");
      } else {
        LOGGER.info("set savepointPath to: " + savepointPath.toString());
        this.flinkInterpreter.getFlinkConfiguration()
                .setString("execution.savepoint.path", savepointPath.toString());
      }
    }
    int defaultSqlParallelism = this.tbenv.getConfig().getConfiguration()
            .getInteger(ExecutionConfigOptions.TABLE_EXEC_RESOURCE_DEFAULT_PARALLELISM);
    try {
      if (context.getLocalProperties().containsKey("parallelism")) {
        this.tbenv.getConfig().getConfiguration()
                .set(ExecutionConfigOptions.TABLE_EXEC_RESOURCE_DEFAULT_PARALLELISM,
                        Integer.parseInt(context.getLocalProperties().get("parallelism")));
      }

      String streamType = context.getLocalProperties().get("type");
      if (streamType == null) {
        throw new IOException("type must be specified for stream sql");
      }
      if (streamType.equalsIgnoreCase("single")) {
        SingleRowStreamSqlJob streamJob = new SingleRowStreamSqlJob(
                flinkInterpreter.getStreamExecutionEnvironment(),
                flinkInterpreter.getStreamTableEnvironment(), context,
                flinkInterpreter.getDefaultParallelism());
        streamJob.run(sql);
      } else if (streamType.equalsIgnoreCase("ts")) {
        TimeSeriesStreamSqlJob streamJob = new TimeSeriesStreamSqlJob(
                flinkInterpreter.getStreamExecutionEnvironment(),
                flinkInterpreter.getStreamTableEnvironment(), context,
                flinkInterpreter.getDefaultParallelism());
        streamJob.run(sql);
      } else if (streamType.equalsIgnoreCase("retract")) {
        RetractStreamSqlJob streamJob = new RetractStreamSqlJob(
                flinkInterpreter.getStreamExecutionEnvironment(),
                flinkInterpreter.getStreamTableEnvironment(), context,
                flinkInterpreter.getDefaultParallelism());
        streamJob.run(sql);
      } else {
        throw new IOException("Unrecognized stream type: " + streamType);
      }
    } finally {
      this.tbenv.getConfig().getConfiguration()
              .set(ExecutionConfigOptions.TABLE_EXEC_RESOURCE_DEFAULT_PARALLELISM,
                      defaultSqlParallelism);
    }
  }

  @Override
  public void cancel(InterpreterContext context) throws InterpreterException {
    this.flinkInterpreter.getZeppelinContext().setInterpreterContext(context);
    this.flinkInterpreter.getZeppelinContext().setNoteGui(context.getNoteGui());
    this.flinkInterpreter.getZeppelinContext().setGui(context.getGui());
    this.flinkInterpreter.getJobManager().cancelJob(context);
  }

  @Override
  public Interpreter.FormType getFormType() throws InterpreterException {
    return Interpreter.FormType.SIMPLE;
  }

  @Override
  public int getProgress(InterpreterContext context) throws InterpreterException {
    return 0;
  }

  @Override
  public Scheduler getScheduler() {
    int maxConcurrency = Integer.parseInt(
            getProperty("zeppelin.flink.concurrentStreamSql.max", "10"));
    return SchedulerFactory.singleton().createOrGetParallelScheduler(
            FlinkStreamSqlInterpreter.class.getName() + this.hashCode(), maxConcurrency);
  }
}
