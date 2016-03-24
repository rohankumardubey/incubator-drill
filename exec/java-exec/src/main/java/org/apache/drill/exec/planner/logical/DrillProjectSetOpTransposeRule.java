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

import org.apache.calcite.plan.ConventionTraitDef;
import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.rel.core.SetOp;
import org.apache.calcite.rel.rules.ProjectSetOpTransposeRule;
import org.apache.calcite.rel.rules.PushProjector;

public class DrillProjectSetOpTransposeRule extends ProjectSetOpTransposeRule {
  public final static RelOptRule INSTANCE = new DrillProjectSetOpTransposeRule(DrillConditions.PRESERVE_ITEM);

  // Since Calcite's default FilterSetOpTransposeRule would match Filter on top of setOp, it potentially will match Rels with mixed CONVENTION trait.
  // Override match method, such that the rule matchs with Rel in the same CONVENTION.

  private DrillProjectSetOpTransposeRule(PushProjector.ExprCondition preserveExprCondition) {
    super(preserveExprCondition);
  }

  @Override
  public boolean matches(RelOptRuleCall call) {
    final Project project = call.rel(0);
    final SetOp setOp = call.rel(1);

    return project.getTraitSet().getTrait(ConventionTraitDef.INSTANCE) == setOp.getTraitSet().getTrait(ConventionTraitDef.INSTANCE);
  }
}