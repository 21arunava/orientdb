/*
 * Copyright 2010-2012 Luca Garulli (l.garulli--at--orientechnologies.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.orientechnologies.orient.core.index.hashindex.local;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import com.orientechnologies.common.directmemory.ODirectMemory;
import com.orientechnologies.common.directmemory.ODirectMemoryFactory;
import com.orientechnologies.orient.core.config.OGlobalConfiguration;
import com.orientechnologies.orient.core.db.record.ODatabaseRecord;
import com.orientechnologies.orient.core.exception.OConfigurationException;
import com.orientechnologies.orient.core.index.OIndexFactory;
import com.orientechnologies.orient.core.index.OIndexInternal;
import com.orientechnologies.orient.core.index.hashindex.local.arc.OLRUBuffer;
import com.orientechnologies.orient.core.storage.impl.local.OStorageLocal;

/**
 * 
 * 
 * @author <a href="mailto:enisher@gmail.com">Artem Orobets</a>
 */
public class OHashIndexFactory implements OIndexFactory {
  public static final Set<String>            SUPPORTED_TYPES = Collections.singleton(OUniqueHashIndex.TYPE_ID);
  private static AtomicReference<OLRUBuffer> BUFFER          = new AtomicReference<OLRUBuffer>();

  @Override
  public Set<String> getTypes() {
    return SUPPORTED_TYPES;
  }

  @Override
  public OIndexInternal<?> createIndex(ODatabaseRecord iDatabase, String iIndexType) throws OConfigurationException {
    if (OUniqueHashIndex.TYPE_ID.equals(iIndexType)) {
      if (BUFFER.get() == null) {
        final ODirectMemory directMemory = ODirectMemoryFactory.INSTANCE.directMemory();
        if (directMemory == null)
          throw new OConfigurationException("There is no suitable direct memory implementation for this platform."
              + " Index creation was canceled.");

        if (!(iDatabase.getStorage() instanceof OStorageLocal))
          throw new OConfigurationException("Given configuration works only for local storage.");

        final long bufferMaxMemory = OGlobalConfiguration.HASH_INDEX_BUFFER_SIZE.getValueAsLong() * 1024 * 1024;

        OLRUBuffer buffer = new OLRUBuffer(bufferMaxMemory, directMemory, OHashIndexBucket.MAX_BUCKET_SIZE_BYTES,
            (OStorageLocal) iDatabase.getStorage(), false);

        BUFFER.compareAndSet(null, buffer);
      }

      return new OUniqueHashIndex(BUFFER.get());
    }

    throw new OConfigurationException("Unsupported type : " + iIndexType);
  }
}
