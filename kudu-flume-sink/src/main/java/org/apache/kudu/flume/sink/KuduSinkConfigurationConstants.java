/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.kudu.flume.sink;

import org.apache.kudu.annotations.InterfaceAudience;
import org.apache.kudu.annotations.InterfaceStability;

@InterfaceAudience.Public
@InterfaceStability.Evolving
public class KuduSinkConfigurationConstants {
  /**
   * Comma-separated list of "host:port" Kudu master addresses.
   * The port is optional and defaults to the Kudu Java client's default master
   * port.
   */
  public static final String MASTER_ADDRESSES = "masterAddresses";

  /**
   * The name of the table in Kudu to write to.
   */
  public static final String TABLE_NAME = "tableName";


/*

  */
/**
   * 是否采用自定义key
   *
   * true
   * false
   *
   *//*


  public static final String CUSTOM_KEY = "customKey";



  */
/**
   * 是否生成随机key？
   * 1，生成随机key
   * 2，生成随机key+字段
   * 3，字段1+字段2
   *
   * uuid
   * col1
   * col1,col2
   *
   *//*

  public static final String KEY_NAME = "keyName";

*/


  /**
   * The fully qualified class name of the KuduOperationsProducer class that the
   * sink should use.
   */
  public static final String PRODUCER = "producer";

  /**
   * Prefix for configuration parameters that are passed to the
   * KuduOperationsProducer.
   */
  public static final String PRODUCER_PREFIX = PRODUCER + ".";

  /**
   * Maximum number of events that the sink should take from the channel per
   * transaction.
   */
  public static final String BATCH_SIZE = "batchSize";

  /**
   * Timeout period for Kudu operations, in milliseconds.
   */
  public static final String TIMEOUT_MILLIS = "timeoutMillis";

  /**
   * Whether to ignore duplicate primary key errors caused by inserts.
   */
  public static final String IGNORE_DUPLICATE_ROWS = "ignoreDuplicateRows";
}





