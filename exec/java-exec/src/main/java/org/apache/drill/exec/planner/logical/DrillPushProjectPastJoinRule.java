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
package org.apache.drill.exec.planner.logical;

import org.apache.calcite.rel.core.Filter;
import org.apache.calcite.rel.core.Join;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.rel.core.RelFactories;
import org.apache.calcite.rel.rules.ProjectJoinTransposeRule;
import org.apache.calcite.rel.rules.PushProjector;
import org.apache.calcite.plan.RelOptRule;

public class DrillPushProjectPastJoinRule extends ProjectJoinTransposeRule {

  public static final RelOptRule INSTANCE = new DrillPushProjectPastJoinRule(DrillConditions.PRESERVE_ITEM);

  public final static RelOptRule DRILL_LOGICAL_INSTANCE = new DrillPushProjectPastJoinRule(DrillProjectRel.class,
      DrillJoinRel.class,
      DrillRelFactories.DRILL_LOGICAL_PROJECT_FACTORY,
      DrillRelFactories.DRILL_LOGICAL_FILTER_FACTORY,
      DrillConditions.PRESERVE_ITEM);

  protected DrillPushProjectPastJoinRule(PushProjector.ExprCondition preserveExprCondition) {
    super(preserveExprCondition);
  }

  protected DrillPushProjectPastJoinRule(Class<? extends Project> projectClass,
                                           Class<? extends Join> joinClass,
                                           RelFactories.ProjectFactory projectFactory,
                                           RelFactories.FilterFactory filterFactory,
                                           PushProjector.ExprCondition preserveExprCondition) {
    super(projectClass, joinClass, projectFactory, filterFactory, preserveExprCondition);
  }

}
