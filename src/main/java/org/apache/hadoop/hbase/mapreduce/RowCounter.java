/**
 * Copyright 2008 The Apache Software Foundation
 *
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
package org.apache.hadoop.hbase.mapreduce;

import java.io.IOException;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.filter.FirstKeyOnlyFilter;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.lib.output.NullOutputFormat;
import org.apache.hadoop.util.GenericOptionsParser;

/**
 * A job with a just a map phase to count rows. Map outputs table rows IF the
 * input row has columns that have content.
 */
public class RowCounter {

  /** Name of this 'program'. */
  static final String NAME = "rowcounter";

  /**
   * Mapper that runs the count.
   */
  static class RowCounterMapper
  extends TableMapper<ImmutableBytesWritable, Result> {

    /** Counter enumeration to count the actual rows. */
    public static enum Counters {ROWS}

    /**
     * Maps the data.
     *
     * @param row  The current table row key.
     * @param values  The columns.
     * @param context  The current context.
     * @throws IOException When something is broken with the data.
     * @see org.apache.hadoop.mapreduce.Mapper#map(KEYIN, VALUEIN,
     *   org.apache.hadoop.mapreduce.Mapper.Context)
     */
    @Override
    public void map(ImmutableBytesWritable row, Result values,
      Context context)
    throws IOException {
      for (KeyValue value: values.list()) {
        if (value.getValue().length > 0) {
          context.getCounter(Counters.ROWS).increment(1);
          break;
        }
      }
    }
  }

  /**
   * Sets up the actual job.
   *
   * @param conf  The current configuration.
   * @param args  The command line parameters.
   * @return The newly created job.
   * @throws IOException When setting up the job fails.
   */
  public static Job createSubmittableJob(Configuration conf, String[] args)
  throws IOException {
    String tableName = args[0];
    String startKey = null;
    String endKey = null;
    StringBuilder sb = new StringBuilder();

    // First argument is table name, starting from second
    for (int i = 1; i < args.length; i++) {
      final String rangeSwitch = "--range=";
      if (args[i].startsWith(rangeSwitch)) {
        String[] startEnd = args[i].substring(rangeSwitch.length()).split(",", 2);
        if (startEnd.length != 2 || startEnd[1].contains(",")) {
          printUsage("Please specify range in such format as \"--range=a,b\" " +
              "or, with only one boundary, \"--range=,b\" or \"--range=a,\"");
          return null;
        }
        startKey = startEnd[0];
        endKey = startEnd[1];
      } 
      else {
        // if no switch, assume column names
        sb.append(args[i]);
        sb.append(" ");
      }
    }

    Job job = new Job(conf, NAME + "_" + tableName);
    job.setJarByClass(RowCounter.class);
    Scan scan = new Scan();
    if (startKey != null && !startKey.equals("")) {
      scan.setStartRow(Bytes.toBytes(startKey));
    }
    if (endKey != null && !endKey.equals("")) {
      scan.setStopRow(Bytes.toBytes(endKey));
    }
    scan.setFilter(new FirstKeyOnlyFilter());
    if (sb.length() > 0) {
      for (String columnName : sb.toString().trim().split(" ")) {
        String [] fields = columnName.split(":");
        if(fields.length == 1) {
          scan.addFamily(Bytes.toBytes(fields[0]));
        } else {
          scan.addColumn(Bytes.toBytes(fields[0]), Bytes.toBytes(fields[1]));
        }
      }
    }
    job.setOutputFormatClass(NullOutputFormat.class);
    TableMapReduceUtil.initTableMapperJob(tableName, scan,
      RowCounterMapper.class, ImmutableBytesWritable.class, Result.class, job);
    job.setNumReduceTasks(0);
    return job;
  }

  /*
   * @param errorMessage Can attach a message when error occurs.
   */
  private static void printUsage(String errorMessage) {
    System.err.println("ERROR: " + errorMessage);
    printUsage();
  }

  /*
   * Prints usage without error message
   */
  private static void printUsage() {
    System.err.println("Usage: RowCounter <tablename> " +
        "[--range=[startKey],[endKey]] [<column1> <column2>...]");
  }

  /**
   * Main entry point.
   *
   * @param args  The command line parameters.
   * @throws Exception When running the job fails.
   */
  public static void main(String[] args) throws Exception {
    Configuration conf = HBaseConfiguration.create();
    String[] otherArgs = new GenericOptionsParser(conf, args).getRemainingArgs();
    if (otherArgs.length < 1) {
      printUsage("Wrong number of parameters: " + args.length);
      System.exit(-1);
    }
    Job job = createSubmittableJob(conf, otherArgs);
    if (job == null) {
      System.exit(-1);
    }
    System.exit(job.waitForCompletion(true) ? 0 : 1);
  }
}
