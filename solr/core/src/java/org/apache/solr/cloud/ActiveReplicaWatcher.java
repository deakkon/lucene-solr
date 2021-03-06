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
package org.apache.solr.cloud;

import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

import org.apache.solr.common.cloud.CollectionStateWatcher;
import org.apache.solr.common.cloud.DocCollection;
import org.apache.solr.common.cloud.Replica;
import org.apache.solr.common.cloud.Slice;
import org.apache.solr.common.cloud.ZkStateReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Watch for replicas to become {@link org.apache.solr.common.cloud.Replica.State#ACTIVE}. Watcher is
 * terminated (its {@link #onStateChanged(Set, DocCollection)} method returns false) when all listed
 * replicas become active.
 * <p>Additionally, the provided {@link CountDownLatch} instance can be used to await
 * for all listed replicas to become active.</p>
 */
public class ActiveReplicaWatcher implements CollectionStateWatcher {
  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
  private final String collection;
  private final List<String> replicaIds = new ArrayList<>();
  private final List<String> solrCoreNames = new ArrayList<>();
  private final List<Replica> activeReplicas = new ArrayList<>();

  private CountDownLatch countDownLatch;

  /**
   * Construct the watcher. At least one replicaId or solrCoreName must be provided.
   * @param collection collection name
   * @param replicaIds list of replica id-s
   * @param solrCoreNames list of SolrCore names
   * @param countDownLatch optional latch to await for all provided replicas to become active. This latch will be
   *                       counted down by at most the number of provided replica id-s / SolrCore names.
   */
  public ActiveReplicaWatcher(String collection, List<String> replicaIds, List<String> solrCoreNames, CountDownLatch countDownLatch) {
    if (replicaIds == null && solrCoreNames == null) {
      throw new IllegalArgumentException("Either replicaId or solrCoreName must be provided.");
    }
    if (replicaIds != null) {
      this.replicaIds.addAll(replicaIds);
    }
    if (solrCoreNames != null) {
      this.solrCoreNames.addAll(solrCoreNames);
    }
    if (this.replicaIds.isEmpty() && this.solrCoreNames.isEmpty()) {
      throw new IllegalArgumentException("At least one replicaId or solrCoreName must be provided");
    }
    this.collection = collection;
    this.countDownLatch = countDownLatch;
  }

  /**
   * Collection name.
   */
  public String getCollection() {
    return collection;
  }

  /**
   * Return the list of active replicas found so far.
   */
  public List<Replica> getActiveReplicas() {
    return activeReplicas;
  }

  /**
   * Return the list of replica id-s that are not active yet (or unverified).
   */
  public List<String> getReplicaIds() {
    return replicaIds;
  }

  /**
   * Return a list of SolrCore names that are not active yet (or unverified).
   */
  public List<String> getSolrCoreNames() {
    return solrCoreNames;
  }

  @Override
  public String toString() {
    return "ActiveReplicaWatcher{" +
        "collection='" + collection + '\'' +
        ", replicaIds=" + replicaIds +
        ", solrCoreNames=" + solrCoreNames +
        ", activeReplicas=" + activeReplicas +
        '}';
  }

  @Override
  public boolean onStateChanged(Set<String> liveNodes, DocCollection collectionState) {
    log.debug("-- onStateChanged: " + collectionState);
    if (collectionState == null) { // collection has been deleted - don't wait
      if (countDownLatch != null) {
        for (int i = 0; i < replicaIds.size() + solrCoreNames.size(); i++) {
          countDownLatch.countDown();
        }
      }
      replicaIds.clear();
      solrCoreNames.clear();
      return true;
    }
    for (Slice slice : collectionState.getSlices()) {
      for (Replica replica : slice.getReplicas()) {
        if (replicaIds.contains(replica.getName())) {
          if (replica.isActive(liveNodes)) {
            activeReplicas.add(replica);
            replicaIds.remove(replica.getName());
            if (countDownLatch != null) {
              countDownLatch.countDown();
            }
          }
        } else if (solrCoreNames.contains(replica.getStr(ZkStateReader.CORE_NAME_PROP))) {
          if (replica.isActive(liveNodes)) {
            activeReplicas.add(replica);
            solrCoreNames.remove(replica.getStr(ZkStateReader.CORE_NAME_PROP));
            if (countDownLatch != null) {
              countDownLatch.countDown();
            }
          }
        }
      }
    }
    if (replicaIds.isEmpty() && solrCoreNames.isEmpty()) {
      return true;
    } else {
      return false;
    }
  }
}
