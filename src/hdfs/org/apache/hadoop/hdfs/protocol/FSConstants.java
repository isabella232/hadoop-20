/**
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
package org.apache.hadoop.hdfs.protocol;

import org.apache.hadoop.conf.Configuration;

/************************************
 * Some handy constants
 *
 ************************************/
public interface FSConstants {
  public static int MIN_BLOCKS_FOR_WRITE = 5;

  // Chunk the block Invalidate message
  public static final int BLOCK_INVALIDATE_CHUNK = 100;

  // Long that indicates "leave current quota unchanged"
  public static final long QUOTA_DONT_SET = Long.MAX_VALUE;
  public static final long QUOTA_RESET = -1L;

  //
  // Timeouts, constants
  //
  public static long HEARTBEAT_INTERVAL = 3;
  public static long BLOCKREPORT_INTERVAL = 60 * 60 * 1000;
  public static long BLOCKREPORT_INITIAL_DELAY = 0;
  public static final long LEASE_SOFTLIMIT_PERIOD = 60 * 1000;
  public static final long LEASE_HARDLIMIT_PERIOD = 60 * LEASE_SOFTLIMIT_PERIOD;
  public static final long LEASE_RECOVER_PERIOD = 10 * 1000; //in ms
  // The number of times the client should retry renewing the lease once it
  // receives a SocketException.
  public static final int MAX_LEASE_RENEWAL_RETRIES = 10;

  // We need to limit the length and depth of a path in the filesystem.  HADOOP-438
  // Currently we set the maximum length to 8k characters and the maximum depth to 1k.
  public static int MAX_PATH_LENGTH = 8000;
  public static int MAX_PATH_DEPTH = 1000;

  public static final int BUFFER_SIZE = new Configuration().getInt("io.file.buffer.size", 4096);
  //Used for writing header etc.
  public static final int SMALL_BUFFER_SIZE = Math.min(BUFFER_SIZE/2, 512);
  //TODO mb@media-style.com: should be conf injected?
  public static final long DEFAULT_BLOCK_SIZE = 64 * 1024 * 1024;
  public static final int DEFAULT_DATA_SOCKET_SIZE = 128 * 1024;

  public static final int SIZE_OF_INTEGER = Integer.SIZE / Byte.SIZE;

  // SafeMode actions
  public enum SafeModeAction{ SAFEMODE_LEAVE, SAFEMODE_ENTER, SAFEMODE_GET; }

  // type of the datanode report
  public static enum DatanodeReportType {ALL, LIVE, DEAD }

  /**
   * Distributed upgrade actions:
   *
   * 1. Get upgrade status.
   * 2. Get detailed upgrade status.
   * 3. Proceed with the upgrade if it is stuck, no matter what the status is.
   */
  public static enum UpgradeAction {
    GET_STATUS,
    DETAILED_STATUS,
    FORCE_PROCEED;
  }

  // The RBW layout version which has the rbw dir under current/
  public static final int RBW_LAYOUT_VERSION = -34;

  // The layout version for the FSImage format in which we store the last edit
  // written to the FSImage alongwith the FSImage.
  public static final int STORED_TXIDS= -37;

  // Version is reflected in the dfs image and edit log files.
  // Version is reflected in the data storage file.
  // Versions are negative.
  // Decrement LAYOUT_VERSION to define a new version.
  public static final int LAYOUT_VERSION = -37;
  // Current version: 
  // -37: persist last transaction id in FSImage.
  public static final int FEDERATION_VERSION = -35;
  
  public static final String DFS_SOFT_LEASE_KEY = "dfs.softlease.period";
  public static final String DFS_HARD_LEASE_KEY = "dfs.hardlease.period";

  // Convert the bytes to KB
  public static int KB_RIGHT_SHIFT_BITS = 10;
  public static int KB_RIGHT_SHIFT_MIN = 1024;
  
  // federation related properties
  public static final String DFS_FEDERATION_NAMESERVICES = "dfs.federation.nameservices";
  public static final String DFS_FEDERATION_NAMESERVICE_ID = "dfs.federation.nameservice.id";
  
  public static final String DFS_DATANODE_IPC_ADDRESS_KEY = "dfs.datanode.ipc.address";
  
  public static final String  DFS_NAMENODE_RPC_ADDRESS_KEY = "dfs.namenode.rpc-address";
  public static final String  DFS_NAMENODE_HTTP_ADDRESS_KEY = "dfs.http.address";
  public static final String  DFS_NAMENODE_SECONDARY_HTTP_ADDRESS_KEY = "dfs.secondary.http.address";
  public static final String  DFS_RAIDNODE_HTTP_ADDRESS_KEY = "dfs.raid.http.address";

  public static final String DFS_NAMENODE_NAME_DIR_KEY = "dfs.name.dir";
  public static final String DFS_NAMENODE_EDITS_DIR_KEY = "dfs.name.edits.dir";
  public static final String DFS_NAMENODE_CHECKPOINT_DIR_KEY = "fs.checkpoint.dir";
  public static final String DFS_NAMENODE_CHECKPOINT_EDITS_DIR_KEY = "fs.checkpoint.edits.dir";

  public static final long MIN_INTERVAL_CHECK_DIR_MSEC = 300 * 1000;

  public static String FS_NAMENODE_ALIASES = "fs.default.name.aliases";
  public static String DFS_NAMENODE_DN_ALIASES = "dfs.namenode.dn-address.aliases";
  public static String DFS_HTTP_ALIASES = "dfs.http.address.aliases";
}
