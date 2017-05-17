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
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.drill.exec.physical.impl;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.Maps;
import io.netty.buffer.DrillBuf;
import org.apache.drill.common.exceptions.ExecutionSetupException;
import org.apache.drill.common.exceptions.UserException;
import org.apache.drill.common.expression.SchemaPath;
import org.apache.drill.common.map.CaseInsensitiveMap;
import org.apache.drill.common.types.TypeProtos.MinorType;
import org.apache.drill.common.types.Types;
import org.apache.drill.exec.exception.OutOfMemoryException;
import org.apache.drill.exec.exception.SchemaChangeException;
import org.apache.drill.exec.expr.TypeHelper;
import org.apache.drill.exec.memory.BufferAllocator;
import org.apache.drill.exec.ops.FragmentContext;
import org.apache.drill.exec.ops.OperatorContext;
import org.apache.drill.exec.ops.OperatorExecContext;
import org.apache.drill.exec.physical.base.PhysicalOperator;
import org.apache.drill.exec.record.BatchSchema;
import org.apache.drill.exec.record.BatchSchema.SelectionVectorMode;
import org.apache.drill.exec.record.CloseableRecordBatch;
import org.apache.drill.exec.record.MaterializedField;
import org.apache.drill.exec.record.TypedFieldId;
import org.apache.drill.exec.record.VectorContainer;
import org.apache.drill.exec.record.VectorWrapper;
import org.apache.drill.exec.record.WritableBatch;
import org.apache.drill.exec.record.selection.SelectionVector2;
import org.apache.drill.exec.record.selection.SelectionVector4;
import org.apache.drill.exec.store.RecordReader;
import org.apache.drill.exec.testing.ControlsInjector;
import org.apache.drill.exec.testing.ControlsInjectorFactory;
import org.apache.drill.exec.util.CallBack;
import org.apache.drill.exec.vector.AllocationHelper;
import org.apache.drill.exec.vector.NullableVarCharVector;
import org.apache.drill.exec.vector.SchemaChangeCallBack;
import org.apache.drill.exec.vector.ValueVector;

import java.util.Collections;
import java.util.Iterator;
import java.util.Map;

/**
 * Record batch used for a particular scan. Operators against one or more
 */
public class ScanBatch implements CloseableRecordBatch {
  private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(ScanBatch.class);
  private static final ControlsInjector injector = ControlsInjectorFactory.getInjector(ScanBatch.class);

  /** Main collection of fields' value vectors. */
  private final VectorContainer container = new VectorContainer();

  private int recordCount;
  private final FragmentContext context;
  private final OperatorContext oContext;
  private Iterator<RecordReader> readers;
  private RecordReader currentReader;
  private BatchSchema schema;
  private final Mutator mutator;
  private boolean done = false;
  private Map<String, ValueVector> implicitVectors = Maps.newHashMap();
  private Iterator<Map<String, String>> implicitColumns;
  private Map<String, String> implicitValues;
  private final BufferAllocator allocator;

  /**
   *
   * @param subScanConfig
   * @param context
   * @param oContext
   * @param readers
   * @param implicitColumns : either an emptylist's iterator when all the readers do not have implicit
   *                        columns, or there is a one-to-one mapping between reader iterator and implicitColumns iterator.
   */
  public ScanBatch(PhysicalOperator subScanConfig, FragmentContext context,
                   OperatorContext oContext, Iterator<RecordReader> readers,
                   Iterator<Map<String, String>> implicitColumns) {
    this.context = context;
    this.readers = readers;
    this.implicitColumns = implicitColumns;
    if (!readers.hasNext()) {
      throw UserException.systemError(
          new ExecutionSetupException("A scan batch must contain at least one reader."))
        .build(logger);
    }

    this.oContext = oContext;
    allocator = oContext.getAllocator();
    mutator = new Mutator(oContext, allocator, container);

    try {
      oContext.getStats().startProcessing();
      advanceNextReader();
      addImplicitVectors();
    } catch (ExecutionSetupException e) {
      try {
        currentReader.close();
      } catch(final Exception e2) {
        logger.error("Close failed for reader " + currentReader.getClass().getSimpleName(), e2);
      }
      throw UserException.systemError(e)
            .addContext("Setup failed for", currentReader.getClass().getSimpleName())
            .build(logger);
    } finally {
      oContext.getStats().stopProcessing();
    }

  }

  public ScanBatch(PhysicalOperator subScanConfig, FragmentContext context,
                   Iterator<RecordReader> readers)
      throws ExecutionSetupException {
    this(subScanConfig, context,
        context.newOperatorContext(subScanConfig),
        readers, Collections.<Map<String, String>> emptyList().iterator());
  }

  @Override
  public FragmentContext getContext() {
    return context;
  }

  @Override
  public BatchSchema getSchema() {
    return schema;
  }

  @Override
  public int getRecordCount() {
    return recordCount;
  }

  @Override
  public void kill(boolean sendUpstream) {
    if (sendUpstream) {
      done = true;
    } else {
      releaseAssets();
    }
  }

  @Override
  public IterOutcome next() {
    if (done) {
      return IterOutcome.NONE;
    }
    oContext.getStats().startProcessing();
    try {
      while (true) {
        try {
          injector.injectChecked(context.getExecutionControls(), "next-allocate", OutOfMemoryException.class);
          currentReader.allocate(mutator.fieldVectorMap());
        } catch (OutOfMemoryException e) {
          clearFieldVectorMap();
          throw UserException.memoryError(e).build(logger);
        }

        recordCount = currentReader.next();
        Preconditions.checkArgument(recordCount >= 0,
            "recordCount from RecordReader.next() should not be negative");

        boolean isNewRegularSchema = mutator.isNewSchema();
        // We should skip the reader, when recordCount = 0 && ! isNewRegularSchema.
        // Add/set implicit column vectors, only when reader gets > 0 row, or
        // when reader gets 0 row but with a schema with new field added
        if (recordCount > 0 || isNewRegularSchema) {
          addImplicitVectors();
          populateImplicitVectors();
        }

        boolean isNewImplicitSchema = mutator.isNewSchema();
        for (VectorWrapper<?> w : container) {
          w.getValueVector().getMutator().setValueCount(recordCount);
        }
        final boolean isNewSchema = isNewRegularSchema || isNewImplicitSchema;
        oContext.getStats().batchReceived(0, recordCount, isNewSchema);

        if (recordCount == 0) {
          currentReader.close();
          if (isNewSchema) {
            // current reader presents a new schema in mutator even though it has 0 row.
            // This could happen when data sources have a non-trivial schema with 0 row.
            container.buildSchema(SelectionVectorMode.NONE);
            schema = container.getSchema();
            if (readers.hasNext()) {
              advanceNextReader();
            } else {
              done = true;  // indicates the follow-up next() call will return IterOutcome.NONE.
            }
            return IterOutcome.OK_NEW_SCHEMA;
          } else { // not a new schema
            if (readers.hasNext()) {
              advanceNextReader();
              continue; // skip reader returning 0 row and having same schema.
                        // Skip to next loop iteration with next available reader.
            } else {
              releaseAssets(); // All data has been read. Release resource.
              return IterOutcome.NONE;
            }
          }
        } else { // recordCount > 0
          if (isNewSchema) {
            container.buildSchema(SelectionVectorMode.NONE);
            schema = container.getSchema();
            return IterOutcome.OK_NEW_SCHEMA;
          } else {
            return IterOutcome.OK;
          }
        }
      }
    } catch (OutOfMemoryException ex) {
      throw UserException.memoryError(ex).build(logger);
    } catch (Exception ex) {
      throw UserException.systemError(ex).build(logger);
    } finally {
      oContext.getStats().stopProcessing();
    }
  }

  private void releaseAssets() {
    container.zeroVectors();
  }

  private void clearFieldVectorMap() {
    for (final ValueVector v : mutator.fieldVectorMap().values()) {
      v.clear();
    }
  }

  private void advanceNextReader() throws ExecutionSetupException {
    currentReader = readers.next();
    implicitValues = implicitColumns.hasNext() ? implicitColumns.next() : null;
    currentReader.setup(oContext, mutator);
  }

  private void addImplicitVectors() {
    try {
      for (ValueVector v : implicitVectors.values()) {
        v.clear();
      }
      implicitVectors.clear();

      if (implicitValues != null) {
        for (String column : implicitValues.keySet()) {
          final MaterializedField field = MaterializedField.create(column, Types.optional(MinorType.VARCHAR));
          @SuppressWarnings("resource")
          final ValueVector v = mutator.addField(field, NullableVarCharVector.class);
          implicitVectors.put(column, v);
        }
      }
    } catch(SchemaChangeException e) {
      // No exception should be thrown here.
      throw UserException.systemError(e)
        .addContext("Failure while allocating implicit vectors")
        .build(logger);
    }
  }

  private void populateImplicitVectors() {
    if (implicitValues != null) {
      for (Map.Entry<String, String> entry : implicitValues.entrySet()) {
        @SuppressWarnings("resource")
        final NullableVarCharVector v = (NullableVarCharVector) implicitVectors.get(entry.getKey());
        String val;
        if ((val = entry.getValue()) != null) {
          AllocationHelper.allocate(v, recordCount, val.length());
          final byte[] bytes = val.getBytes();
          for (int j = 0; j < recordCount; j++) {
            v.getMutator().setSafe(j, bytes, 0, bytes.length);
          }
          v.getMutator().setValueCount(recordCount);
        } else {
          AllocationHelper.allocate(v, recordCount, 0);
          v.getMutator().setValueCount(recordCount);
        }
      }
    }
  }

  @Override
  public SelectionVector2 getSelectionVector2() {
    throw new UnsupportedOperationException();
  }

  @Override
  public SelectionVector4 getSelectionVector4() {
    throw new UnsupportedOperationException();
  }

  @Override
  public TypedFieldId getValueVectorId(SchemaPath path) {
    return container.getValueVectorId(path);
  }

  @Override
  public VectorWrapper<?> getValueAccessorById(Class<?> clazz, int... ids) {
    return container.getValueAccessorById(clazz, ids);
  }

  /**
   * Row set mutator implementation provided to record readers created by
   * this scan batch. Made visible so that tests can create this mutator
   * without also needing a ScanBatch instance. (This class is really independent
   * of the ScanBatch, but resides here for historical reasons. This is,
   * in turn, the only use of the generated vector readers in the vector
   * package.)
   */

  @VisibleForTesting
  public static class Mutator implements OutputMutator {
    /** Flag keeping track whether top-level schema has changed since last inquiry (via #isNewSchema}).
     * It's initialized to false, or reset to false after #isNewSchema or after #clear, until a new value vector
     * or a value vector with different type is added to fieldVectorMap.
     **/
    private boolean schemaChanged;

    /** Fields' value vectors indexed by fields' keys. */
    private final CaseInsensitiveMap<ValueVector> fieldVectorMap =
            CaseInsensitiveMap.newHashMap();

    private final SchemaChangeCallBack callBack = new SchemaChangeCallBack();
    private final BufferAllocator allocator;

    private final VectorContainer container;

    private final OperatorExecContext oContext;

    public Mutator(OperatorExecContext oContext, BufferAllocator allocator, VectorContainer container) {
      this.oContext = oContext;
      this.allocator = allocator;
      this.container = container;
      this.schemaChanged = false;
    }

    public Map<String, ValueVector> fieldVectorMap() {
      return fieldVectorMap;
    }

    @SuppressWarnings("resource")
    @Override
    public <T extends ValueVector> T addField(MaterializedField field,
                                              Class<T> clazz) throws SchemaChangeException {
      // Check if the field exists.
      ValueVector v = fieldVectorMap.get(field.getPath());
      if (v == null || v.getClass() != clazz) {
        // Field does not exist--add it to the map and the output container.
        v = TypeHelper.getNewVector(field, allocator, callBack);
        if (!clazz.isAssignableFrom(v.getClass())) {
          throw new SchemaChangeException(
              String.format(
                  "The class that was provided, %s, does not correspond to the "
                  + "expected vector type of %s.",
                  clazz.getSimpleName(), v.getClass().getSimpleName()));
        }

        final ValueVector old = fieldVectorMap.put(field.getPath(), v);
        if (old != null) {
          old.clear();
          container.remove(old);
        }

        container.add(v);
        // Added new vectors to the container--mark that the schema has changed.
        schemaChanged = true;
      }

      return clazz.cast(v);
    }

    @Override
    public void allocate(int recordCount) {
      for (final ValueVector v : fieldVectorMap.values()) {
        AllocationHelper.allocate(v, recordCount, 50, 10);
      }
    }

    /**
     * Reports whether schema has changed (field was added or re-added) since
     * last call to {@link #isNewSchema}.  Returns true at first call.
     */
    @Override
    public boolean isNewSchema() {
      // Check if top-level schema or any of the deeper map schemas has changed.

      // Note:  Callback's getSchemaChangedAndReset() must get called in order
      // to reset it and avoid false reports of schema changes in future.  (Be
      // careful with short-circuit OR (||) operator.)

      final boolean deeperSchemaChanged = callBack.getSchemaChangedAndReset();
      if (schemaChanged || deeperSchemaChanged) {
        schemaChanged = false;
        return true;
      }
      return false;
    }

    @Override
    public DrillBuf getManagedBuffer() {
      return oContext.getManagedBuffer();
    }

    @Override
    public CallBack getCallBack() {
      return callBack;
    }

    public void clear() {
      fieldVectorMap.clear();
      schemaChanged = false;
    }
  }

  @Override
  public Iterator<VectorWrapper<?>> iterator() {
    return container.iterator();
  }

  @Override
  public WritableBatch getWritableBatch() {
    return WritableBatch.get(this);
  }

  @Override
  public void close() throws Exception {
    container.clear();
    for (final ValueVector v : implicitVectors.values()) {
      v.clear();
    }
    mutator.clear();
    currentReader.close();
  }

  @Override
  public VectorContainer getOutgoingContainer() {
    throw new UnsupportedOperationException(
        String.format("You should not call getOutgoingContainer() for class %s",
                      this.getClass().getCanonicalName()));
  }
}
