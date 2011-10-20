/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.accumulo.examples.client;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map.Entry;
import java.util.Random;

import org.apache.accumulo.core.client.AccumuloException;
import org.apache.accumulo.core.client.AccumuloSecurityException;
import org.apache.accumulo.core.client.BatchScanner;
import org.apache.accumulo.core.client.Connector;
import org.apache.accumulo.core.client.TableNotFoundException;
import org.apache.accumulo.core.client.ZooKeeperInstance;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Range;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.security.Authorizations;
import org.apache.hadoop.io.Text;
import org.apache.log4j.Logger;

class CountingVerifyingReceiver {
  private static final Logger log = Logger.getLogger(CountingVerifyingReceiver.class);
  
  long count = 0;
  int expectedValueSize = 0;
  HashMap<Text,Boolean> expectedRows;
  
  CountingVerifyingReceiver(HashMap<Text,Boolean> expectedRows, int expectedValueSize) {
    this.expectedRows = expectedRows;
    this.expectedValueSize = expectedValueSize;
  }
  
  public void receive(Key key, Value value) {
    
    String row = key.getRow().toString();
    long rowid = Integer.parseInt(row.split("_")[1]);
    
    byte expectedValue[] = RandomBatchWriter.createValue(rowid, expectedValueSize);
    
    if (!Arrays.equals(expectedValue, value.get())) {
      log.error("Got unexpected value for " + key + " expected : " + new String(expectedValue) + " got : " + new String(value.get()));
    }
    
    if (!expectedRows.containsKey(key.getRow())) {
      log.error("Got unexpected key " + key);
    } else {
      expectedRows.put(key.getRow(), true);
    }
    
    count++;
  }
}

public class RandomBatchScanner {
  private static final Logger log = Logger.getLogger(CountingVerifyingReceiver.class);
  
  static void generateRandomQueries(int num, long min, long max, Random r, HashSet<Range> ranges, HashMap<Text,Boolean> expectedRows) {
    log.info(String.format("Generating %,d random queries...", num));
    while (ranges.size() < num) {
      long rowid = (Math.abs(r.nextLong()) % (max - min)) + min;
      
      Text row1 = new Text(String.format("row_%010d", rowid));
      
      Range range = new Range(new Text(row1));
      ranges.add(range);
      expectedRows.put(row1, false);
    }
    
    log.info("finished");
  }
  
  private static void printRowsNotFound(HashMap<Text,Boolean> expectedRows) {
    int count = 0;
    for (Entry<Text,Boolean> entry : expectedRows.entrySet())
      if (!entry.getValue()) count++;
    
    if (count > 0) log.warn("Did not find " + count + " rows");
  }
  
  static void doRandomQueries(int num, long min, long max, int evs, Random r, BatchScanner tsbr) {
    
    HashSet<Range> ranges = new HashSet<Range>(num);
    HashMap<Text,Boolean> expectedRows = new java.util.HashMap<Text,Boolean>();
    
    generateRandomQueries(num, min, max, r, ranges, expectedRows);
    
    tsbr.setRanges(ranges);
    
    CountingVerifyingReceiver receiver = new CountingVerifyingReceiver(expectedRows, evs);
    
    long t1 = System.currentTimeMillis();
    
    for (Entry<Key,Value> entry : tsbr) {
      receiver.receive(entry.getKey(), entry.getValue());
    }
    
    long t2 = System.currentTimeMillis();
    
    log.info(String.format("%6.2f lookups/sec %6.2f secs\n", num / ((t2 - t1) / 1000.0), ((t2 - t1) / 1000.0)));
    log.info(String.format("num results : %,d\n", receiver.count));
    
    printRowsNotFound(expectedRows);
  }
  
  public static void main(String[] args) throws AccumuloException, AccumuloSecurityException, TableNotFoundException {
    String seed = null;
    
    int index = 0;
    String processedArgs[] = new String[11];
    for (int i = 0; i < args.length; i++) {
      if (args[i].equals("-s")) {
        seed = args[++i];
      } else {
        processedArgs[index++] = args[i];
      }
    }
    
    if (index != 11) {
      System.out
          .println("Usage : RandomBatchScanner [-s <seed>] <instance name> <zoo keepers> <username> <password> <table> <num> <min> <max> <expected value size> <num threads> <auths>");
      return;
    }
    
    String instanceName = processedArgs[0];
    String zooKeepers = processedArgs[1];
    String user = processedArgs[2];
    byte[] pass = processedArgs[3].getBytes();
    String table = processedArgs[4];
    int num = Integer.parseInt(processedArgs[5]);
    long min = Long.parseLong(processedArgs[6]);
    long max = Long.parseLong(processedArgs[7]);
    int expectedValueSize = Integer.parseInt(processedArgs[8]);
    int numThreads = Integer.parseInt(processedArgs[9]);
    String auths = processedArgs[10];
    
    // Uncomment the following lines for detailed debugging info
    // Logger logger = Logger.getLogger(Constants.CORE_PACKAGE_NAME);
    // logger.setLevel(Level.TRACE);
    
    ZooKeeperInstance instance = new ZooKeeperInstance(instanceName, zooKeepers);
    Connector connector = instance.getConnector(user, pass);
    BatchScanner tsbr = connector.createBatchScanner(table, new Authorizations(auths.split(",")), numThreads);
    
    Random r;
    if (seed == null) r = new Random();
    else r = new Random(Long.parseLong(seed));
    
    // do one cold
    doRandomQueries(num, min, max, expectedValueSize, r, tsbr);
    
    System.gc();
    System.gc();
    System.gc();
    
    if (seed == null) r = new Random();
    else r = new Random(Long.parseLong(seed));
    
    // do one hot (connections already established, metadata table cached)
    doRandomQueries(num, min, max, expectedValueSize, r, tsbr);
    
    tsbr.close();
  }
}
