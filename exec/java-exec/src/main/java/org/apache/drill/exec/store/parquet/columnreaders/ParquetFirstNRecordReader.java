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
package org.apache.drill.exec.store.parquet.columnreaders;

import org.apache.drill.common.exceptions.ExecutionSetupException;
import org.apache.drill.common.expression.SchemaPath;
import org.apache.drill.exec.ops.FragmentContext;
import org.apache.hadoop.fs.FileSystem;
import org.apache.parquet.hadoop.CodecFactory;
import org.apache.parquet.hadoop.metadata.ParquetMetadata;

import java.util.List;

public class ParquetFirstNRecordReader extends ParquetRecordReader{
  public ParquetFirstNRecordReader(FragmentContext fragmentContext, String path, int rowGroupIndex, FileSystem fs, CodecFactory codecFactory, ParquetMetadata footer, List<SchemaPath> columns, long firstNRows) throws ExecutionSetupException {
    super(fragmentContext, path, rowGroupIndex, fs, codecFactory, footer, columns);
    this.totalRecordsReadToRead = firstNRows;
  }

  public ParquetFirstNRecordReader(
      FragmentContext fragmentContext,
      long batchSize,
      String path,
      int rowGroupIndex,
      FileSystem fs,
      CodecFactory codecFactory,
      ParquetMetadata footer,
      List<SchemaPath> columns,
      long firstNRows) throws ExecutionSetupException {
    super(fragmentContext, batchSize, path, rowGroupIndex, fs, codecFactory, footer, columns);
    this.totalRecordsReadToRead = firstNRows;
  }

  @Override
  public int next() {
    if (totalRecordsRead >= totalRecordsReadToRead) {
      return 0;
    }

    return super.next();
  }
}