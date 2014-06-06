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

<@pp.dropOutputFile />
<#list vv.types as type>
<#list type.minor as minor>
<#list ["", "Nullable"] as nullMode>
<#assign lowerName = minor.class?uncap_first />
<#if lowerName == "int" ><#assign lowerName = "integer" /></#if>
<#assign name = minor.class?cap_first />
<#assign javaType = (minor.javaType!type.javaType) />
<#assign friendlyType = (minor.friendlyType!minor.boxedType!type.boxedType) />
<#assign safeType=friendlyType />
<#if safeType=="byte[]"><#assign safeType="ByteArray" /></#if>

<@pp.changeOutputFile name="/org/apache/drill/exec/vector/complex/impl/${nullMode}${name}SingularReaderImpl.java" />
<#include "/@includes/license.ftl" />

package org.apache.drill.exec.vector.complex.impl;

<#include "/@includes/vv_imports.ftl" />

import java.math.BigDecimal;
import java.math.BigInteger;

import org.apache.drill.exec.expr.holders.*;
import org.joda.time.Period;
import org.mortbay.jetty.servlet.Holder;

@SuppressWarnings("unused")
public class ${nullMode}${name}SingularReaderImpl extends AbstractFieldReader {

  private ${nullMode}${name}Holder holder;
  
  public ${nullMode}${name}SingularReaderImpl(${nullMode}${name}Holder holder) {
    this.holder = holder;
  }
  
  @Override
  public int size() {
    throw new UnsupportedOperationException("You can't call size on a singular value reader.");
  }

  @Override
  public boolean next() {
    throw new UnsupportedOperationException("You can't call next on a single value reader.");
  }

  @Override
  public void setPosition(int index) {
    throw new UnsupportedOperationException("You can't call next on a single value reader.");
  }

  @Override
  public MajorType getType() {
    return this.holder.getType();
  }

  @Override
  public boolean isSet() {
    return this.holder.isSet();
  }
  
  @Override
  public ${friendlyType} read${safeType}(){   
    
    <#if nullMode == "Nullable">
    
    if (!holder.isSet()) {
      return null;
    }
    </#if>
    
    <#if type.major == "VarLen">
    
      int length = holder.end - holder.start;
      byte[] value = new byte [length];
      holder.buffer.getBytes(holder.start, value, 0, length);
    
      <#if minor.class == "VarBinary">
      return value;
      <#elseif minor.class == "Var16Char">
      return new String(value);
      <#elseif minor.class == "VarChar">
      return new Text();
      </#if>
    
    <#elseif minor.class == "Interval">      
      Period p = new Period();
      return p.plusMonths(holder.months).plusDays(holder.days).plusMillis(holder.milliSeconds);
    
    <#elseif minor.class == "IntervalDay">
      Period p = new Period();
      return p.plusDays(holder.days).plusMillis(holder.milliSeconds);
   
    <#elseif minor.class == "Decimal9" ||  
             minor.class == "Decimal18" >
      BigInteger value = BigInteger.valueOf(holder.value);
      return new BigDecimal(value, holder.scale);
    
    <#elseif minor.class == "Decimal28Dense"  ||    
             minor.class == "Decimal28Sparse" || 
             minor.class == "Decimal38Dense"  || 
             minor.class == "Decimal38Sparse" >
      return null;
    <#elseif minor.class == "Bit" >
      return new Boolean(holder.value != 0);
    <#else>  
      ${friendlyType} value = new ${friendlyType}(this.holder.value);
      return value;
    </#if>
  
  }
         
}

</#list>
</#list>
</#list>
