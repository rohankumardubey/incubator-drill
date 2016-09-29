/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.drill.exec.store.parquet;

import com.google.common.collect.Sets;
import org.apache.drill.common.expression.ErrorCollector;
import org.apache.drill.common.expression.ErrorCollectorImpl;
import org.apache.drill.common.expression.ExpressionStringBuilder;
import org.apache.drill.common.expression.LogicalExpression;
import org.apache.drill.common.expression.SchemaPath;
import org.apache.drill.common.expression.visitors.AbstractExprVisitor;
import org.apache.drill.common.map.CaseInsensitiveMap;
import org.apache.drill.common.types.TypeProtos;
import org.apache.drill.exec.compile.sig.ConstantExpressionIdentifier;
import org.apache.drill.exec.expr.ExpressionTreeMaterializer;
import org.apache.drill.exec.expr.fn.FunctionLookupContext;
import org.apache.drill.exec.expr.stat.ParquetFilterPredicate;
import org.apache.drill.exec.expr.stat.ParquetPredicates;
import org.apache.drill.exec.expr.stat.RangeExprEvaluator;
import org.apache.drill.exec.ops.FragmentContext;
import org.apache.drill.exec.ops.UdfUtilities;
import org.apache.drill.exec.server.options.OptionManager;
import org.apache.drill.exec.store.ParquetOutputRecordWriter;
import org.apache.drill.exec.store.parquet.columnreaders.ParquetToDrillTypeConverter;
import org.apache.parquet.column.ColumnDescriptor;
import org.apache.parquet.column.statistics.IntStatistics;
import org.apache.parquet.column.statistics.LongStatistics;
import org.apache.parquet.column.statistics.Statistics;
import org.apache.parquet.filter2.predicate.FilterPredicate;
import org.apache.parquet.filter2.statisticslevel.StatisticsFilter;
import org.apache.parquet.format.FileMetaData;
import org.apache.parquet.format.SchemaElement;
import org.apache.parquet.format.converter.ParquetMetadataConverter;
import org.apache.parquet.hadoop.ParquetFileWriter;
import org.apache.parquet.hadoop.metadata.ColumnChunkMetaData;
import org.apache.parquet.hadoop.metadata.ParquetMetadata;
import org.joda.time.DateTimeUtils;

import java.util.Collection;
import java.util.List;
import java.util.Set;

import static org.apache.drill.exec.store.ParquetOutputRecordWriter.JULIAN_DAY_EPOC;

public class ParquetRGFilterEvaluator {
  static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(ParquetRGFilterEvaluator.class);

  public static boolean evalFilter(LogicalExpression expr, List<ColumnChunkMetaData> columnChunkMetaDatas) {
    FilterPredicate predicate = ParquetFilterBuilderAG.buildParquetFilterPredicate(expr);
    if (predicate != null) {
      return StatisticsFilter.canDrop(predicate, columnChunkMetaDatas);
    }
    return false;
  }

  public static boolean evalFilter(LogicalExpression expr, ParquetMetadata footer, int rowGroupIndex, OptionManager options, FragmentContext fragmentContext) {
    // figure out the set of columns referenced in expression.
    final Collection<SchemaPath> schemaPathsInExpr = expr.accept(new FieldReferenceFinder(), null);
    final CaseInsensitiveMap<SchemaPath> columnInExprMap = CaseInsensitiveMap.newHashMap();
    for (final SchemaPath path : schemaPathsInExpr) {
      columnInExprMap.put(path.getRootSegment().getPath(), path);
    }

    // map from column name to ColumnDescriptor
    CaseInsensitiveMap<ColumnDescriptor> columnDescMap = CaseInsensitiveMap.newHashMap();
    for (final ColumnDescriptor column : footer.getFileMetaData().getSchema().getColumns()) {
      columnDescMap.put(column.getPath()[0], column);
    }

    // map from column name to SchemeElement
    final FileMetaData fileMetaData = new ParquetMetadataConverter().toParquetMetadata(ParquetFileWriter.CURRENT_VERSION, footer);
    final CaseInsensitiveMap<SchemaElement> schemaElementMap = CaseInsensitiveMap.newHashMap();
    for (final SchemaElement se : fileMetaData.getSchema()) {
      schemaElementMap.put(se.getName(), se);
    }

    // map from column name to ColumnChunkMetaData
    final CaseInsensitiveMap<ColumnChunkMetaData> columnStatMap = CaseInsensitiveMap.newHashMap();
    for (final ColumnChunkMetaData colMetaData: footer.getBlocks().get(rowGroupIndex).getColumns()) {
      columnStatMap.put(colMetaData.getPath().toDotString(), colMetaData);
    }

    // map from column name to MajorType
    final CaseInsensitiveMap<TypeProtos.MajorType> columnTypeMap = CaseInsensitiveMap.newHashMap();

    // map from column name to column stat expression.
    CaseInsensitiveMap<Statistics> statMap = CaseInsensitiveMap.newHashMap();

    for (final String path : columnInExprMap.keySet()) {
      if (columnDescMap.containsKey(path) && schemaElementMap.containsKey(path) && columnDescMap.containsKey(path)) {
        ColumnDescriptor columnDesc =  columnDescMap.get(path);
        SchemaElement se = schemaElementMap.get(path);
        ColumnChunkMetaData metaData = columnStatMap.get(path);

        TypeProtos.MajorType type = ParquetToDrillTypeConverter.toMajorType(columnDesc.getType(), se.getType_length(),
            getDataMode(columnDesc), se, options);
        columnTypeMap.put(path, type);

        if (metaData != null) {
          Statistics stat = convertStatIfNecessary(metaData.getStatistics(), type.getMinorType());
          statMap.put(path, stat);
        }
      }
    }

    ErrorCollector errorCollector = new ErrorCollectorImpl();

    LogicalExpression materializedFilter = ExpressionTreeMaterializer.materializeFilterExpr(expr, columnTypeMap, errorCollector, fragmentContext.getFunctionRegistry());

    if (errorCollector.hasErrors()) {
      logger.error("{} error(s) encountered when materialize filter expression : {}", errorCollector.getErrorCount(), errorCollector.toErrorString());
      return false;
    }

    logger.debug("materializedFilter : {}", ExpressionStringBuilder.toString(materializedFilter));

    ParquetFilterPredicate parquetPredicate = (ParquetFilterPredicate) ParquetFilterBuilder.buildParquetFilterPredicate(materializedFilter);

    Set<LogicalExpression> constantBoundaries = ConstantExpressionIdentifier.getConstantExpressionSet(materializedFilter);

    RangeExprEvaluator rangeExprEvaluator = new RangeExprEvaluator(statMap, constantBoundaries, fragmentContext);

    boolean canDrop = false;
    if (parquetPredicate != null) {
      canDrop = parquetPredicate.canDrop(rangeExprEvaluator);
    }

    logger.debug(" canDrop {} ", canDrop);

    return canDrop;
  }

  private static TypeProtos.DataMode getDataMode(ColumnDescriptor column) {
    if (column.getMaxRepetitionLevel() > 0 ) {
      return TypeProtos.DataMode.REPEATED;
    } else if (column.getMaxDefinitionLevel() == 0) {
      return TypeProtos.DataMode.REQUIRED;
    } else {
      return TypeProtos.DataMode.OPTIONAL;
    }
  }

  private static Statistics convertStatIfNecessary(Statistics stat, TypeProtos.MinorType type) {
    if (type != TypeProtos.MinorType.DATE) {
      return stat;
    } else {
      IntStatistics dateStat = (IntStatistics) stat;
      LongStatistics dateMLS = new LongStatistics();
      dateMLS.setMinMax(convertToDrillDateValue(dateStat.getMin()), convertToDrillDateValue(dateStat.getMax()));
      dateMLS.setNumNulls(dateStat.getNumNulls());
      return dateMLS;
    }
  }

  private static long convertToDrillDateValue(int dateValue) {
    long  dateInMillis = DateTimeUtils.fromJulianDay(dateValue - ParquetOutputRecordWriter.JULIAN_DAY_EPOC - 0.5);
//    // Specific for date column created by Drill CTAS prior fix for DRILL-4203.
//    // Apply the same shift as in ParquetOutputRecordWriter.java for data value.
//    final int intValue = (int) (DateTimeUtils.toJulianDayNumber(dateInMillis) + JULIAN_DAY_EPOC);
//    return intValue;
    return dateInMillis;

  }

  /**
   * Search through a LogicalExpression, finding all internal schema path references and returning them in a set.
   */
  private static class FieldReferenceFinder extends AbstractExprVisitor<Set<SchemaPath>, Void, RuntimeException> {
    @Override
    public Set<SchemaPath> visitSchemaPath(SchemaPath path, Void value) {
      Set<SchemaPath> set = Sets.newHashSet();
      set.add(path);
      return set;
    }

    @Override
    public Set<SchemaPath> visitUnknown(LogicalExpression e, Void value) {
      Set<SchemaPath> paths = Sets.newHashSet();
      for (LogicalExpression ex : e) {
        paths.addAll(ex.accept(this, null));
      }
      return paths;
    }
  }
}