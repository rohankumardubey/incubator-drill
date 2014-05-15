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

package org.apache.drill.exec.physical.config;

import com.fasterxml.jackson.annotation.JsonTypeName;

import java.util.Iterator;
import java.util.List;

import org.apache.drill.common.expression.FieldReference;
import org.apache.drill.common.logical.data.JoinCondition;
import org.apache.drill.common.logical.data.NamedExpression;
import org.apache.drill.exec.physical.OperatorCost;
import org.apache.drill.exec.physical.base.AbstractBase;
import org.apache.drill.exec.physical.base.PhysicalOperator;
import org.apache.drill.exec.physical.base.PhysicalVisitor;
import org.apache.drill.exec.physical.base.Size;

import com.beust.jcommander.internal.Lists;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import com.google.common.collect.Iterators;
import org.apache.drill.exec.physical.impl.common.HashTable;
import org.apache.drill.exec.physical.impl.common.HashTableConfig;

import org.eigenbase.rel.JoinRelType;

@JsonTypeName("hash-join")
public class HashJoinPOP extends AbstractBase {
    static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(HashJoinPOP.class);


    private final PhysicalOperator left;
    private final PhysicalOperator right;
    private final List<JoinCondition> conditions;
    private final JoinRelType joinType;

    @Override
    public OperatorCost getCost() {
        return new OperatorCost(0,0,0,0);
    }

    @JsonCreator
    public HashJoinPOP(
            @JsonProperty("left") PhysicalOperator left,
            @JsonProperty("right") PhysicalOperator right,
            @JsonProperty("conditions") List<JoinCondition> conditions,
            @JsonProperty("joinType") JoinRelType joinType
    ) {
        this.left = left;
        this.right = right;
        this.conditions = conditions;
        Preconditions.checkArgument(joinType != null, "Join type is missing!");
        this.joinType = joinType;
    }

    @Override
    public Size getSize() {
        return left.getSize().add(right.getSize());
    }

    @Override
    public <T, X, E extends Throwable> T accept(PhysicalVisitor<T, X, E> physicalVisitor, X value) throws E {
        return physicalVisitor.visitHashJoin(this, value);
    }

    @Override
    public PhysicalOperator getNewWithChildren(List<PhysicalOperator> children) {
        Preconditions.checkArgument(children.size() == 2);
        return new HashJoinPOP(children.get(0), children.get(1), conditions, joinType);
    }

    @Override
    public Iterator<PhysicalOperator> iterator() {
        return Iterators.forArray(left, right);
    }

    public PhysicalOperator getLeft() {
        return left;
    }

    public PhysicalOperator getRight() {
        return right;
    }

    public JoinRelType getJoinType() {
        return joinType;
    }

    public List<JoinCondition> getConditions() {
        return conditions;
    }

    public HashJoinPOP flipIfRight(){
        if(joinType == JoinRelType.RIGHT){
            List<JoinCondition> flippedConditions = Lists.newArrayList(conditions.size());
            for(JoinCondition c : conditions){
                flippedConditions.add(c.flip());
            }
            return new HashJoinPOP(right, left, flippedConditions, JoinRelType.LEFT);
        }else{
            return this;
        }
    }
}
