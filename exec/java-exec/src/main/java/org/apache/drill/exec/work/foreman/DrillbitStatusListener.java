/**
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
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.drill.exec.work.foreman;


import org.apache.drill.exec.proto.CoordinationProtos;

import java.util.Set;

/**
 * Interface to define the listener to keep track the active drillbits in the cluster, and what's the action to take
 * if the set of active drillbits is changed.
 */
public interface DrillbitStatusListener {

  static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(DrillbitStatusListener.class);

  /**
   * The action to take when the set of active drillbits in the cluster is changed.
   * @param activeDrillbits the set of active drillbits in the cluster.
   */
  public void drillbitStatusChanged(Set<CoordinationProtos.DrillbitEndpoint> activeDrillbits);

}
