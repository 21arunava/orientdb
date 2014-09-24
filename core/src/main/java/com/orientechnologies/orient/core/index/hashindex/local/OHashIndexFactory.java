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

import com.orientechnologies.orient.core.db.record.ODatabaseRecordInternal;
import com.orientechnologies.orient.core.exception.OConfigurationException;
import com.orientechnologies.orient.core.index.ODefaultIndexFactory;
import com.orientechnologies.orient.core.index.OIndexDictionary;
import com.orientechnologies.orient.core.index.OIndexEngine;
import com.orientechnologies.orient.core.index.OIndexException;
import com.orientechnologies.orient.core.index.OIndexFactory;
import com.orientechnologies.orient.core.index.OIndexFullText;
import com.orientechnologies.orient.core.index.OIndexInternal;
import com.orientechnologies.orient.core.index.OIndexNotUnique;
import com.orientechnologies.orient.core.index.OIndexUnique;
import com.orientechnologies.orient.core.index.engine.OHashTableIndexEngine;
import com.orientechnologies.orient.core.index.engine.ORemoteIndexEngine;
import com.orientechnologies.orient.core.metadata.schema.OClass;
import com.orientechnologies.orient.core.record.impl.ODocument;
import com.orientechnologies.orient.core.storage.OStorage;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 
 * 
 * @author <a href="mailto:enisher@gmail.com">Artem Orobets</a>
 */
public class OHashIndexFactory implements OIndexFactory {

  private static final Set<String> TYPES;
  public static final String       SBTREE_ALGORITHM   = "SBTREE";
  public static final String       MVRBTREE_ALGORITHM = "MVRBTREE";
  private static final Set<String> ALGORITHMS;
  static {
    final Set<String> types = new HashSet<String>();
    types.add(OClass.INDEX_TYPE.UNIQUE_HASH_INDEX.toString());
    types.add(OClass.INDEX_TYPE.NOTUNIQUE_HASH_INDEX.toString());
    types.add(OClass.INDEX_TYPE.FULLTEXT_HASH_INDEX.toString());
    types.add(OClass.INDEX_TYPE.DICTIONARY_HASH_INDEX.toString());
    TYPES = Collections.unmodifiableSet(types);
  }
  static {
    final Set<String> algorithms = new HashSet<String>();
    algorithms.add(SBTREE_ALGORITHM);
    algorithms.add(MVRBTREE_ALGORITHM);
    ALGORITHMS = Collections.unmodifiableSet(algorithms);
  }

  /**
   * Index types :
   * <ul>
   * <li>UNIQUE</li>
   * <li>NOTUNIQUE</li>
   * <li>FULLTEXT</li>
   * <li>DICTIONARY</li>
   * </ul>
   */
  public Set<String> getTypes() {
    return TYPES;
  }

  public Set<String> getAlgorithms() {
    return ALGORITHMS;
  }

  public OIndexInternal<?> createIndex(ODatabaseRecordInternal database, String indexType, String algorithm,
      String valueContainerAlgorithm, ODocument metadata) throws OConfigurationException {
    if (valueContainerAlgorithm == null)
      valueContainerAlgorithm = ODefaultIndexFactory.NONE_VALUE_CONTAINER;

    OStorage storage = database.getStorage();
    OIndexEngine indexEngine;

    Boolean durableInNonTxMode;
    Object durable = null;
    if (metadata != null)
      durable = metadata.field("durableInNonTxMode");

    if (durable instanceof Boolean)
      durableInNonTxMode = (Boolean) durable;
    else
      durableInNonTxMode = null;

    final String storageType = storage.getType();
    if (storageType.equals("memory") || storageType.equals("plocal"))
      indexEngine = new OHashTableIndexEngine(durableInNonTxMode);
    else if (storageType.equals("distributed"))
      // DISTRIBUTED CASE: HANDLE IT AS FOR LOCAL
      indexEngine = new OHashTableIndexEngine(durableInNonTxMode);
    else if (storageType.equals("remote"))
      indexEngine = new ORemoteIndexEngine();
    else
      throw new OIndexException("Unsupported storage type : " + storageType);

    if (OClass.INDEX_TYPE.UNIQUE_HASH_INDEX.toString().equals(indexType))
      return new OIndexUnique(indexType, algorithm, indexEngine, valueContainerAlgorithm, metadata);
    else if (OClass.INDEX_TYPE.NOTUNIQUE_HASH_INDEX.toString().equals(indexType))
      return new OIndexNotUnique(indexType, algorithm, indexEngine, valueContainerAlgorithm, metadata);
    else if (OClass.INDEX_TYPE.FULLTEXT_HASH_INDEX.toString().equals(indexType))
      return new OIndexFullText(indexType, algorithm, indexEngine, valueContainerAlgorithm, metadata);
    else if (OClass.INDEX_TYPE.DICTIONARY_HASH_INDEX.toString().equals(indexType))
      return new OIndexDictionary(indexType, algorithm, indexEngine, valueContainerAlgorithm, metadata);

    throw new OConfigurationException("Unsupported type : " + indexType);
  }
}
