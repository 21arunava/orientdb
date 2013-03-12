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

import java.io.IOException;
import java.util.Comparator;
import java.util.Iterator;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

import com.orientechnologies.common.comparator.ODefaultComparator;
import com.orientechnologies.common.concur.resource.OSharedResourceAdaptive;
import com.orientechnologies.common.directmemory.ODirectMemory;
import com.orientechnologies.common.directmemory.ODirectMemoryFactory;
import com.orientechnologies.common.log.OLogManager;
import com.orientechnologies.common.serialization.types.OBinarySerializer;
import com.orientechnologies.orient.core.config.OGlobalConfiguration;
import com.orientechnologies.orient.core.config.OStorageFileConfiguration;
import com.orientechnologies.orient.core.config.OStorageSegmentConfiguration;
import com.orientechnologies.orient.core.index.OIndexException;
import com.orientechnologies.orient.core.index.hashindex.local.arc.OLRUBuffer;
import com.orientechnologies.orient.core.storage.fs.OFileFactory;
import com.orientechnologies.orient.core.storage.impl.local.OStorageLocal;
import com.orientechnologies.orient.core.storage.impl.local.OStorageVariableParser;
import com.orientechnologies.orient.core.storage.impl.local.eh.OEHFileMetadataStore;
import com.orientechnologies.orient.core.storage.impl.local.eh.OEHTreeStateStore;
import com.orientechnologies.orient.core.storage.impl.memory.eh.OEHNodeMetadata;

/**
 * @author Andrey Lomakin
 * @since 12.03.13
 */
public class OLocalHashTable<K, V> extends OSharedResourceAdaptive {
  private static final double         MERGE_THRESHOLD        = 0.2;

  private long[][]                    hashTree;
  private OEHNodeMetadata[]           nodesMetadata;

  private int                         hashTreeSize;
  private long                        size;

  private int                         hashTreeTombstone      = -1;
  private long                        bucketTombstonePointer = -1;

  private final String                metadataConfigurationFileExtension;
  private final String                treeStateFileExtension;
  private final String                bucketFileExtension;

  public static final int             MAX_LEVEL_DEPTH        = 8;
  public static final int             MAX_LEVEL_SIZE         = 1 << MAX_LEVEL_DEPTH;

  public static final int             LEVEL_MASK             = Integer.MAX_VALUE >>> (31 - MAX_LEVEL_DEPTH);

  private final OStorageLocal         storage;

  private String                      name;

  private OEHFileMetadataStore        metadataStore;
  private OEHTreeStateStore           treeStateStore;

  private final ODirectMemory         directMemory           = ODirectMemoryFactory.INSTANCE.directMemory();

  private final OLRUBuffer            buffer;
  private final OHashFunction<K>      keyHashFunction;

  private OBinarySerializer<K>        keySerializer;
  private OBinarySerializer<V>        valueSerializer;

  private OHashIndexBufferMetadata[]  filesMetadata          = new OHashIndexBufferMetadata[64];
  private SortedMap<Long, Long>[]     splitBucketsByLevels   = new TreeMap[64];
  private int                         splitBucketsSize;

  private final Comparator<? super K> comparator             = ODefaultComparator.INSTANCE;

  public OLocalHashTable(String metadataConfigurationFileExtension, String treeStateFileExtension, String bucketFileExtension,
      OStorageLocal storage, OLRUBuffer buffer, OHashFunction<K> keyHashFunction) {
    super(OGlobalConfiguration.ENVIRONMENT_CONCURRENT.getValueAsBoolean());
    this.metadataConfigurationFileExtension = metadataConfigurationFileExtension;
    this.treeStateFileExtension = treeStateFileExtension;
    this.bucketFileExtension = bucketFileExtension;
    this.storage = storage;

    this.buffer = buffer;
    this.keyHashFunction = keyHashFunction;
  }

  public void init(String name, OBinarySerializer<K> keySerializer, OBinarySerializer<V> valueSerializer) {
    acquireExclusiveLock();
    try {
      this.name = name;
      this.keySerializer = keySerializer;
      this.valueSerializer = valueSerializer;

      final OStorageFileConfiguration metadataConfiguration = new OStorageFileConfiguration(null,
          OStorageVariableParser.DB_PATH_VARIABLE + '/' + name + metadataConfigurationFileExtension, OFileFactory.MMAP, "0", "50%");

      final OStorageFileConfiguration treeStateConfiguration = new OStorageFileConfiguration(null,
          OStorageVariableParser.DB_PATH_VARIABLE + '/' + name + treeStateFileExtension, OFileFactory.MMAP, "0", "50%");

      metadataStore = new OEHFileMetadataStore(storage, metadataConfiguration);
      treeStateStore = new OEHTreeStateStore(storage, treeStateConfiguration);

      metadataStore.create(-1);
      treeStateStore.create(-1);

      metadataStore.setRecordsCount(size);

      treeStateStore.setHashTreeSize(hashTreeSize);
      treeStateStore.setHashTreeTombstone(hashTreeTombstone);
      treeStateStore.setBucketTombstonePointer(bucketTombstonePointer);

      filesMetadata[0] = createFileMetadata(0);

      initHashTreeState();
    } catch (IOException e) {
      throw new OIndexException("Error during local hash table creation.", e);
    } finally {
      releaseExclusiveLock();
    }

  }

  public OBinarySerializer<K> getKeySerializer() {
    return keySerializer;
  }

  public void setKeySerializer(OBinarySerializer<K> keySerializer) {
    this.keySerializer = keySerializer;
  }

  public OBinarySerializer<V> getValueSerializer() {
    return valueSerializer;
  }

  public void setValueSerializer(OBinarySerializer<V> valueSerializer) {
    this.valueSerializer = valueSerializer;
  }

  private OHashIndexBufferMetadata createFileMetadata(int i) throws IOException {
    final OStorageSegmentConfiguration fileConfiguration = new OStorageSegmentConfiguration(storage.getConfiguration(), name + i, i);
    fileConfiguration.fileType = OFileFactory.CLASSIC;
    fileConfiguration.fileMaxSize = "10000Mb";

    buffer.openFile(fileConfiguration, bucketFileExtension);

    return new OHashIndexBufferMetadata(fileConfiguration, 0, -1);
  }

  public V get(K key) {
    acquireSharedLock();
    try {
      final long hashCode = keyHashFunction.hashCode(key);

      BucketPath bucketPath = getBucket(hashCode);
      final long bucketPointer = hashTree[bucketPath.nodeIndex][bucketPath.itemIndex + bucketPath.hashMapOffset];
      if (bucketPointer == 0)
        return null;

      long pageIndex = getPageIndex(bucketPointer);
      int fileLevel = getFileLevel(bucketPointer);

      PageLockResult pageLockResult = lockPageForRead(pageIndex, fileLevel);

      try {
        final OHashIndexBucket<K, V> bucket = new OHashIndexBucket<K, V>(pageLockResult.dataPointer, directMemory, keySerializer,
            valueSerializer);

        OHashIndexBucket.Entry<K, V> entry = bucket.find(key);
        if (entry == null)
          return null;

        return entry.value;
      } finally {
        releasePageReadLock(pageIndex, fileLevel, pageLockResult.cacheLock);
      }
    } catch (IOException e) {
      throw new OIndexException("Exception during index value retrieval", e);
    } finally {
      releaseSharedLock();
    }
  }

  public void put(K key, V value) {
    doPut(key, value);
  }

  public boolean remove(K key) {
    acquireExclusiveLock();
    try {
      final long hashCode = keyHashFunction.hashCode(key);

      final BucketPath nodePath = getBucket(hashCode);
      final long bucketPointer = hashTree[nodePath.nodeIndex][nodePath.itemIndex + nodePath.hashMapOffset];

      final long pageIndex = getPageIndex(bucketPointer);
      final int fileLevel = getFileLevel(bucketPointer);

      PageLockResult pageLockResult = lockPageForWrite(pageIndex, fileLevel);
      try {
        final OHashIndexBucket<K, V> bucket = new OHashIndexBucket<K, V>(pageLockResult.dataPointer, directMemory, keySerializer,
            valueSerializer);
        final int positionIndex = bucket.getIndex(key);
        if (positionIndex < 0)
          return false;

        bucket.deleteEntry(positionIndex);
        size--;

        if (mergeBucketsAfterDeletion(nodePath, bucket)) {
          clearDirtyFlag(pageIndex, fileLevel, pageLockResult.cacheLock);
        }
      } finally {
        releasePageWriteLock(pageIndex, fileLevel, pageLockResult.cacheLock);
      }

      if (nodePath.parent != null) {
        final int hashMapSize = 1 << nodePath.nodeLocalDepth;

        final long[] node = hashTree[nodePath.nodeIndex];
        final boolean allMapsContainSameBucket = checkAllMapsContainSameBucket(node, hashMapSize);
        if (allMapsContainSameBucket)
          mergeNodeToParent(node, nodePath);
      }

      return true;
    } catch (IOException e) {
      throw new OIndexException("Error during index removal", e);
    } finally {
      releaseExclusiveLock();
    }
  }

  public void clear() {
    acquireExclusiveLock();
    try {
      for (int i = 0; i < filesMetadata.length; i++) {
        if (filesMetadata[i] != null)
          buffer.truncateFile(name + i, bucketFileExtension);

        final SortedMap<Long, Long> splitBuckets = splitBucketsByLevels[i];
        if (splitBuckets != null)
          splitBuckets.clear();
      }

      bucketTombstonePointer = -1;
      metadataStore.truncate();
      treeStateStore.truncate();

      initHashTreeState();
    } catch (InterruptedException e) {
      throw new OIndexException("Error during hash table clear", e);
    } catch (IOException e) {
      throw new OIndexException("Error during hash table clear", e);
    } finally {
      releaseExclusiveLock();
    }
  }

  public OHashIndexBucket.Entry<K, V>[] higherEntries(K key) throws IOException {
    acquireSharedLock();
    try {
      final long hashCode = keyHashFunction.hashCode(key);
      BucketPath bucketPath = getBucket(hashCode);
      long bucketPointer = hashTree[bucketPath.nodeIndex][bucketPath.itemIndex + bucketPath.hashMapOffset];

      int fileLevel = getFileLevel(bucketPointer);
      long pageIndex = getPageIndex(bucketPointer);

      PageLockResult pageLockResult = lockPageForRead(pageIndex, fileLevel);
      try {
        OHashIndexBucket<K, V> bucket = new OHashIndexBucket<K, V>(pageLockResult.dataPointer, directMemory, keySerializer,
            valueSerializer);

        while (bucket.size() == 0 || comparator.compare(bucket.getKey(bucket.size() - 1), key) <= 0) {
          bucketPath = nextBucketToFind(bucketPath, bucket.getDepth());
          if (bucketPath == null)
            return new OHashIndexBucket.Entry[0];

          releasePageReadLock(pageIndex, fileLevel, pageLockResult.cacheLock);

          final long nextPointer = hashTree[bucketPath.nodeIndex][bucketPath.itemIndex + bucketPath.hashMapOffset];

          fileLevel = getFileLevel(nextPointer);
          pageIndex = getPageIndex(nextPointer);

          pageLockResult = lockPageForRead(pageIndex, fileLevel);
          bucket = new OHashIndexBucket<K, V>(pageLockResult.dataPointer, directMemory, keySerializer, valueSerializer);
        }

        final int index = bucket.getIndex(key);
        final int startIndex;
        if (index >= 0)
          startIndex = index + 1;
        else
          startIndex = -index - 1;

        final int endIndex = bucket.size();
        return convertBucketToEntries(bucket, startIndex, endIndex);
      } finally {
        releasePageReadLock(pageIndex, fileLevel, pageLockResult.cacheLock);
      }
    } finally {
      releaseSharedLock();
    }
  }

  private OHashIndexBucket.Entry<K, V>[] convertBucketToEntries(final OHashIndexBucket<K, V> bucket, int startIndex, int endIndex) {
    final OHashIndexBucket.Entry<K, V>[] entries = new OHashIndexBucket.Entry[endIndex - startIndex];
    final Iterator<OHashIndexBucket.Entry<K, V>> iterator = bucket.iterator(startIndex);

    for (int i = 0, k = startIndex; k < endIndex; i++, k++)
      entries[i] = iterator.next();

    return entries;
  }

  private BucketPath nextBucketToFind(final BucketPath bucketPath, int bucketDepth) {
    int offset = bucketPath.nodeGlobalDepth - bucketDepth;

    BucketPath currentNode = bucketPath;
    int nodeLocalDepth = nodesMetadata[bucketPath.nodeIndex].getNodeLocalDepth();
    assert nodesMetadata[bucketPath.nodeIndex].getNodeLocalDepth() == bucketPath.nodeLocalDepth;

    while (offset > 0) {
      offset -= nodeLocalDepth;
      if (offset > 0) {
        currentNode = bucketPath.parent;
        nodeLocalDepth = currentNode.nodeLocalDepth;
        assert nodesMetadata[currentNode.nodeIndex].getNodeLocalDepth() == currentNode.nodeLocalDepth;
      }
    }

    final int diff = bucketDepth - (currentNode.nodeGlobalDepth - nodeLocalDepth);
    final int interval = (1 << (nodeLocalDepth - diff));
    final int firstStartIndex = currentNode.itemIndex & ((LEVEL_MASK << (nodeLocalDepth - diff)) & LEVEL_MASK);

    final BucketPath bucketPathToFind;
    final int globalIndex = firstStartIndex + interval + currentNode.hashMapOffset;
    if (globalIndex >= MAX_LEVEL_SIZE)
      bucketPathToFind = nextLevelUp(currentNode);
    else {
      final int hashMapSize = 1 << currentNode.nodeLocalDepth;
      final int hashMapOffset = globalIndex / hashMapSize * hashMapSize;

      final int startIndex = globalIndex - hashMapOffset;

      bucketPathToFind = new BucketPath(currentNode.parent, hashMapOffset, startIndex, currentNode.nodeIndex,
          currentNode.nodeLocalDepth, currentNode.nodeGlobalDepth);
    }

    return nextNonEmptyNode(bucketPathToFind);
  }

  private BucketPath nextNonEmptyNode(BucketPath bucketPath) {
    nextBucketLoop: while (bucketPath != null) {
      final long[] node = hashTree[bucketPath.nodeIndex];
      final int startIndex = bucketPath.itemIndex + bucketPath.hashMapOffset;
      final int endIndex = MAX_LEVEL_SIZE;

      for (int i = startIndex; i < endIndex; i++) {
        final long position = node[i];

        if (position > 0) {
          final int hashMapSize = 1 << bucketPath.nodeLocalDepth;
          final int hashMapOffset = (i / hashMapSize) * hashMapSize;
          final int itemIndex = i - hashMapOffset;

          return new BucketPath(bucketPath.parent, hashMapOffset, itemIndex, bucketPath.nodeIndex, bucketPath.nodeLocalDepth,
              bucketPath.nodeGlobalDepth);
        }

        if (position < 0) {
          final int childNodeIndex = (int) ((position & Long.MAX_VALUE) >> 8);
          final int childItemOffset = (int) position & 0xFF;

          final BucketPath parent = new BucketPath(bucketPath.parent, 0, i, bucketPath.nodeIndex, bucketPath.nodeLocalDepth,
              bucketPath.nodeGlobalDepth);

          final int childLocalDepth = nodesMetadata[childNodeIndex].getNodeLocalDepth();
          bucketPath = new BucketPath(parent, childItemOffset, 0, childNodeIndex, childLocalDepth, bucketPath.nodeGlobalDepth
              + childLocalDepth);

          continue nextBucketLoop;
        }
      }

      bucketPath = nextLevelUp(bucketPath);
    }

    return null;
  }

  private BucketPath nextLevelUp(BucketPath bucketPath) {
    if (bucketPath.parent == null)
      return null;

    final int nodeLocalDepth = bucketPath.nodeLocalDepth;
    assert nodesMetadata[bucketPath.nodeIndex].getNodeLocalDepth() == bucketPath.nodeLocalDepth;
    final int pointersSize = 1 << (MAX_LEVEL_DEPTH - nodeLocalDepth);

    final BucketPath parent = bucketPath.parent;

    if (parent.itemIndex < MAX_LEVEL_SIZE / 2) {
      final int nextParentIndex = (parent.itemIndex / pointersSize + 1) * pointersSize;
      return new BucketPath(parent.parent, 0, nextParentIndex, parent.nodeIndex, parent.nodeLocalDepth, parent.nodeGlobalDepth);
    }

    final int nextParentIndex = ((parent.itemIndex - MAX_LEVEL_SIZE / 2) / pointersSize + 1) * pointersSize + MAX_LEVEL_SIZE / 2;
    if (nextParentIndex < MAX_LEVEL_SIZE)
      return new BucketPath(parent.parent, 0, nextParentIndex, parent.nodeIndex, parent.nodeLocalDepth, parent.nodeGlobalDepth);

    return nextLevelUp(new BucketPath(parent.parent, 0, MAX_LEVEL_SIZE - 1, parent.nodeIndex, parent.nodeLocalDepth,
        parent.nodeGlobalDepth));
  }

  public OHashIndexBucket.Entry<K, V>[] ceilingEntries(K key) throws IOException {
    acquireSharedLock();
    try {
      final long hashCode = keyHashFunction.hashCode(key);
      BucketPath bucketPath = getBucket(hashCode);

      long bucketPointer = hashTree[bucketPath.nodeIndex][bucketPath.itemIndex + bucketPath.hashMapOffset];

      int fileLevel = getFileLevel(bucketPointer);
      long pageIndex = getPageIndex(bucketPointer);

      PageLockResult pageLockResult = lockPageForRead(pageIndex, fileLevel);
      try {
        OHashIndexBucket<K, V> bucket = new OHashIndexBucket<K, V>(pageLockResult.dataPointer, directMemory, keySerializer,
            valueSerializer);
        while (bucket.size() == 0) {
          bucketPath = nextBucketToFind(bucketPath, bucket.getDepth());
          if (bucketPath == null)
            return new OHashIndexBucket.Entry[0];

          releasePageReadLock(pageIndex, fileLevel, pageLockResult.cacheLock);
          final long nextPointer = hashTree[bucketPath.nodeIndex][bucketPath.itemIndex + bucketPath.hashMapOffset];

          fileLevel = getFileLevel(nextPointer);
          pageIndex = getPageIndex(nextPointer);

          pageLockResult = lockPageForRead(pageIndex, fileLevel);
          bucket = new OHashIndexBucket<K, V>(pageLockResult.dataPointer, directMemory, keySerializer, valueSerializer);
        }

        final int index = bucket.getIndex(key);
        final int startIndex;
        if (index >= 0)
          startIndex = index;
        else
          startIndex = -index - 1;

        final int endIndex = bucket.size();
        return convertBucketToEntries(bucket, startIndex, endIndex);
      } finally {
        releasePageReadLock(pageIndex, fileLevel, pageLockResult.cacheLock);
      }

    } finally {
      releaseSharedLock();
    }
  }

  public OHashIndexBucket.Entry<K, V>[] lowerEntries(K key) throws IOException {
    acquireSharedLock();
    try {
      final long hashCode = keyHashFunction.hashCode(key);
      BucketPath bucketPath = getBucket(hashCode);

      long bucketPointer = hashTree[bucketPath.nodeIndex][bucketPath.itemIndex + bucketPath.hashMapOffset];

      int fileLevel = getFileLevel(bucketPointer);
      long pageIndex = getPageIndex(bucketPointer);

      PageLockResult pageLockResult = lockPageForRead(pageIndex, fileLevel);
      try {
        OHashIndexBucket<K, V> bucket = new OHashIndexBucket<K, V>(pageLockResult.dataPointer, directMemory, keySerializer,
            valueSerializer);
        while (bucket.size() == 0 || comparator.compare(bucket.getKey(0), key) >= 0) {
          final BucketPath prevBucketPath = prevBucketToFind(bucketPath, bucket.getDepth());
          if (prevBucketPath == null)
            return new OHashIndexBucket.Entry[0];
          releasePageReadLock(pageIndex, fileLevel, pageLockResult.cacheLock);

          final long prevPointer = hashTree[prevBucketPath.nodeIndex][prevBucketPath.itemIndex + prevBucketPath.hashMapOffset];

          fileLevel = getFileLevel(prevPointer);
          pageIndex = getPageIndex(prevPointer);

          bucket = new OHashIndexBucket<K, V>(pageLockResult.dataPointer, directMemory, keySerializer, valueSerializer);

          bucketPath = prevBucketPath;
        }

        final int startIndex = 0;
        final int index = bucket.getIndex(key);

        final int endIndex;
        if (index >= 0)
          endIndex = index;
        else
          endIndex = -index - 1;

        return convertBucketToEntries(bucket, startIndex, endIndex);
      } finally {
        releasePageReadLock(pageIndex, fileLevel, pageLockResult.cacheLock);
      }
    } finally {
      releaseSharedLock();
    }
  }

  public OHashIndexBucket.Entry<K, V>[] floorEntries(K key) throws IOException {
    acquireSharedLock();
    try {
      final long hashCode = keyHashFunction.hashCode(key);
      BucketPath bucketPath = getBucket(hashCode);

      long bucketPointer = hashTree[bucketPath.nodeIndex][bucketPath.itemIndex + bucketPath.hashMapOffset];

      int fileLevel = getFileLevel(bucketPointer);
      long pageIndex = getPageIndex(bucketPointer);

      PageLockResult pageLockResult = lockPageForRead(pageIndex, fileLevel);
      try {
        OHashIndexBucket<K, V> bucket = new OHashIndexBucket<K, V>(pageLockResult.dataPointer, directMemory, keySerializer,
            valueSerializer);
        while (bucket.size() == 0) {
          final BucketPath prevBucketPath = prevBucketToFind(bucketPath, bucket.getDepth());
          if (prevBucketPath == null)
            return new OHashIndexBucket.Entry[0];

          releasePageReadLock(pageIndex, fileLevel, pageLockResult.cacheLock);

          final long prevPointer = hashTree[prevBucketPath.nodeIndex][prevBucketPath.itemIndex + prevBucketPath.hashMapOffset];

          fileLevel = getFileLevel(prevPointer);
          pageIndex = getPageIndex(prevPointer);

          bucket = new OHashIndexBucket<K, V>(pageLockResult.dataPointer, directMemory, keySerializer, valueSerializer);

          bucketPath = prevBucketPath;
        }

        final int startIndex = 0;
        final int index = bucket.getIndex(key);

        final int endIndex;
        if (index >= 0)
          endIndex = index + 1;
        else
          endIndex = -index - 1;

        return convertBucketToEntries(bucket, startIndex, endIndex);
      } finally {
        releasePageReadLock(pageIndex, fileLevel, pageLockResult.cacheLock);
      }
    } finally {
      releaseSharedLock();
    }
  }

  private BucketPath prevBucketToFind(final BucketPath bucketPath, int bucketDepth) {
    int offset = bucketPath.nodeGlobalDepth - bucketDepth;

    BucketPath currentBucket = bucketPath;
    int nodeLocalDepth = bucketPath.nodeLocalDepth;
    while (offset > 0) {
      offset -= nodeLocalDepth;
      if (offset > 0) {
        currentBucket = bucketPath.parent;
        nodeLocalDepth = currentBucket.nodeLocalDepth;
      }
    }

    final int diff = bucketDepth - (currentBucket.nodeGlobalDepth - nodeLocalDepth);
    final int firstStartIndex = currentBucket.itemIndex & ((LEVEL_MASK << (nodeLocalDepth - diff)) & LEVEL_MASK);
    final int globalIndex = firstStartIndex + currentBucket.hashMapOffset - 1;

    final BucketPath bucketPathToFind;
    if (globalIndex < 0)
      bucketPathToFind = prevLevelUp(bucketPath);
    else {
      final int hashMapSize = 1 << currentBucket.nodeLocalDepth;
      final int hashMapOffset = globalIndex / hashMapSize * hashMapSize;

      final int startIndex = globalIndex - hashMapOffset;

      bucketPathToFind = new BucketPath(currentBucket.parent, hashMapOffset, startIndex, currentBucket.nodeIndex,
          currentBucket.nodeLocalDepth, currentBucket.nodeGlobalDepth);
    }

    return prevNonEmptyNode(bucketPathToFind);
  }

  private BucketPath prevNonEmptyNode(BucketPath nodePath) {
    prevBucketLoop: while (nodePath != null) {
      final long[] node = hashTree[nodePath.nodeIndex];
      final int startIndex = 0;
      final int endIndex = nodePath.itemIndex + nodePath.hashMapOffset;

      for (int i = endIndex; i >= startIndex; i--) {
        final long position = node[i];
        if (position > 0) {
          final int hashMapSize = 1 << nodePath.nodeLocalDepth;
          final int hashMapOffset = (i / hashMapSize) * hashMapSize;
          final int itemIndex = i - hashMapOffset;

          return new BucketPath(nodePath.parent, hashMapOffset, itemIndex, nodePath.nodeIndex, nodePath.nodeLocalDepth,
              nodePath.nodeGlobalDepth);
        }

        if (position < 0) {
          final int childNodeIndex = (int) ((position & Long.MAX_VALUE) >> 8);
          final int childItemOffset = (int) position & 0xFF;
          final int nodeLocalDepth = nodesMetadata[childNodeIndex].getNodeLocalDepth();
          final int endChildIndex = (1 << nodeLocalDepth) - 1;

          final BucketPath parent = new BucketPath(nodePath.parent, 0, i, nodePath.nodeIndex, nodePath.nodeLocalDepth,
              nodePath.nodeGlobalDepth);
          nodePath = new BucketPath(parent, childItemOffset, endChildIndex, childNodeIndex, nodeLocalDepth, parent.nodeGlobalDepth
              + nodeLocalDepth);
          continue prevBucketLoop;
        }
      }

      nodePath = prevLevelUp(nodePath);
    }

    return null;
  }

  private BucketPath prevLevelUp(BucketPath bucketPath) {
    if (bucketPath.parent == null)
      return null;

    final int nodeLocalDepth = bucketPath.nodeLocalDepth;
    final int pointersSize = 1 << (MAX_LEVEL_DEPTH - nodeLocalDepth);

    final BucketPath parent = bucketPath.parent;

    if (parent.itemIndex > MAX_LEVEL_SIZE / 2) {
      final int prevParentIndex = ((parent.itemIndex - MAX_LEVEL_SIZE / 2) / pointersSize) * pointersSize + MAX_LEVEL_SIZE / 2 - 1;
      return new BucketPath(parent.parent, 0, prevParentIndex, parent.nodeIndex, parent.nodeLocalDepth, parent.nodeGlobalDepth);
    }

    final int prevParentIndex = (parent.itemIndex / pointersSize) * pointersSize - 1;
    if (prevParentIndex >= 0)
      return new BucketPath(parent.parent, 0, prevParentIndex, parent.nodeIndex, parent.nodeLocalDepth, parent.nodeGlobalDepth);

    return prevLevelUp(new BucketPath(parent.parent, 0, 0, parent.nodeIndex, parent.nodeLocalDepth, -1));
  }

  public long size() {
    acquireSharedLock();
    try {
      return size;
    } finally {
      releaseSharedLock();
    }
  }

  public void load() {
    acquireExclusiveLock();
    try {
      metadataStore.open();
      treeStateStore.open();

      size = metadataStore.getRecordsCount();

      hashTreeSize = (int) treeStateStore.getHashTreeSize();
      hashTreeTombstone = (int) treeStateStore.getHashTreeTombstone();
      bucketTombstonePointer = treeStateStore.getBucketTombstonePointer();

      final int arraySize;
      int bitsCount = Integer.bitCount(hashTreeSize);
      if (bitsCount == 1)
        arraySize = hashTreeSize;
      else
        arraySize = 1 << (Integer.highestOneBit(hashTreeSize) + 1);

      hashTree = new long[arraySize][];
      nodesMetadata = new OEHNodeMetadata[arraySize];

      final long bucketsOffset = treeStateStore.getBucketsOffset();

      for (int i = 0; i < hashTreeSize; i++) {
        hashTree[i] = treeStateStore.loadTreeNode(i, bucketsOffset);
        nodesMetadata[i] = treeStateStore.loadMetadata(i, bucketsOffset);
      }

      size = metadataStore.getRecordsCount();
    } catch (IOException e) {
      throw new OIndexException("Exception during hash table loading", e);
    } finally {
      releaseExclusiveLock();
    }
  }

  public void close() {
    acquireExclusiveLock();
    try {
      flushSplitBucketsBuffer();

      if (metadataStore.getFile().isOpen())
        metadataStore.close();

      if (treeStateStore.getFile().isOpen())
        treeStateStore.close();

      for (int i = 0; i < filesMetadata.length; i++)
        if (filesMetadata[i] != null)
          buffer.closeFile(name + i, bucketFileExtension);

    } catch (InterruptedException e) {
      throw new OIndexException("Error during hash table close", e);
    } catch (IOException e) {
      throw new OIndexException("Error during hash table close", e);
    } finally {
      releaseExclusiveLock();
    }
  }

  public void delete() {
    acquireExclusiveLock();
    try {
      for (int i = 0; i < filesMetadata.length; i++) {
        if (filesMetadata[i] != null)
          buffer.deleteFile(name + i, bucketFileExtension);

        SortedMap<Long, Long> splitBuckets = splitBucketsByLevels[i];
        if (splitBuckets != null)
          splitBuckets.clear();
      }

      metadataStore.delete();
      treeStateStore.delete();
    } catch (InterruptedException e) {
      throw new OIndexException("Exception during index deletion", e);
    } catch (IOException e) {
      throw new OIndexException("Exception during index deletion", e);
    } finally {
      releaseExclusiveLock();
    }
  }

  private void mergeNodeToParent(long[] node, BucketPath nodePath) {
    final int startIndex = findParentNodeStartIndex(nodePath);
    final int localNodeDepth = nodePath.nodeLocalDepth;
    final int hashMapSize = 1 << localNodeDepth;

    final long[] parentNode = hashTree[nodePath.parent.nodeIndex];
    for (int i = 0, k = startIndex; i < node.length; i += hashMapSize, k++) {
      parentNode[k] = node[i];
    }

    deleteNode(nodePath.nodeIndex);

    final OEHNodeMetadata metadata = nodesMetadata[nodePath.parent.nodeIndex];
    if (nodePath.parent.itemIndex < MAX_LEVEL_SIZE / 2) {
      final int maxChildDepth = metadata.getMaxLeftChildDepth();
      if (maxChildDepth == localNodeDepth)
        metadata.setMaxLeftChildDepth(getMaxLevelDepth(parentNode, 0, parentNode.length / 2));
    } else {
      final int maxChildDepth = metadata.getMaxRightChildDepth();
      if (maxChildDepth == localNodeDepth)
        metadata.setMaxRightChildDepth(getMaxLevelDepth(parentNode, parentNode.length / 2, parentNode.length));
    }
  }

  private boolean mergeBucketsAfterDeletion(BucketPath nodePath, OHashIndexBucket<K, V> bucket) throws IOException {
    final int bucketDepth = bucket.getDepth();

    if (bucket.getContentSize() > OHashIndexBucket.MAX_BUCKET_SIZE_BYTES * MERGE_THRESHOLD)
      return false;

    if (bucketDepth - MAX_LEVEL_DEPTH < 1)
      return false;

    int offset = nodePath.nodeGlobalDepth - (bucketDepth - 1);
    BucketPath currentNode = nodePath;
    int nodeLocalDepth = nodePath.nodeLocalDepth;
    while (offset > 0) {
      offset -= nodeLocalDepth;
      if (offset > 0) {
        currentNode = nodePath.parent;
        nodeLocalDepth = currentNode.nodeLocalDepth;
      }
    }

    final int diff = bucketDepth - 1 - (currentNode.nodeGlobalDepth - nodeLocalDepth);
    final int interval = (1 << (nodeLocalDepth - diff - 1));

    int firstStartIndex = currentNode.itemIndex & ((LEVEL_MASK << (nodeLocalDepth - diff)) & LEVEL_MASK);
    int firstEndIndex = firstStartIndex + interval;

    final int secondStartIndex = firstEndIndex;
    final int secondEndIndex = secondStartIndex + interval;

    final OHashIndexBucket<K, V> buddyBucket;

    int buddyLevel;
    long buddyIndex;
    long buddyPointer;

    final long[] node = hashTree[currentNode.nodeIndex];
    if ((currentNode.itemIndex >>> (nodeLocalDepth - diff - 1) & 1) == 1) {
      buddyPointer = node[firstStartIndex + currentNode.hashMapOffset];

      while (buddyPointer < 0) {
        final int nodeIndex = (int) ((buddyPointer & Long.MAX_VALUE) >> 8);
        final int itemOffset = (int) buddyPointer & 0xFF;

        buddyPointer = hashTree[nodeIndex][itemOffset];
      }

      assert buddyPointer > 0;

      buddyLevel = getFileLevel(buddyPointer);
      buddyIndex = getPageIndex(buddyPointer);
    } else {
      buddyPointer = node[secondStartIndex + currentNode.hashMapOffset];

      while (buddyPointer < 0) {
        final int nodeIndex = (int) ((buddyPointer & Long.MAX_VALUE) >> 8);
        final int itemOffset = (int) buddyPointer & 0xFF;

        buddyPointer = hashTree[nodeIndex][itemOffset];
      }

      assert buddyPointer > 0;

      buddyLevel = getFileLevel(buddyPointer);
      buddyIndex = getPageIndex(buddyPointer);
    }

    PageLockResult buddyPageLock = lockPageForWrite(buddyIndex, buddyLevel);
    try {
      buddyBucket = new OHashIndexBucket<K, V>(buddyPageLock.dataPointer, directMemory, keySerializer, valueSerializer);

      if (buddyBucket.getDepth() != bucketDepth) {
        clearDirtyFlag(buddyIndex, buddyLevel, buddyPageLock.cacheLock);
        return false;
      }

      if (bucket.mergedSize(buddyBucket) >= OHashIndexBucket.MAX_BUCKET_SIZE_BYTES) {
        clearDirtyFlag(buddyIndex, buddyLevel, buddyPageLock.cacheLock);
        return false;
      }

      filesMetadata[buddyLevel].setBucketsCount(filesMetadata[buddyLevel].getBucketsCount() - 2);

      int newBuddyLevel = buddyLevel - 1;
      long newBuddyIndex = buddyBucket.getSplitHistory(newBuddyLevel);

      filesMetadata[buddyLevel].setBucketsCount(filesMetadata[buddyLevel].getBucketsCount() + 1);

      final PageLockResult newBuddyPageLock = lockPageForWrite(newBuddyIndex, newBuddyLevel);
      try {
        final OHashIndexBucket<K, V> newBuddyBucket = new OHashIndexBucket<K, V>(bucketDepth - 1, newBuddyPageLock.dataPointer,
            directMemory, keySerializer, valueSerializer);

        for (OHashIndexBucket.Entry<K, V> entry : buddyBucket)
          newBuddyBucket.appendEntry(entry.key, entry.value);

        for (OHashIndexBucket.Entry<K, V> entry : bucket)
          newBuddyBucket.addEntry(entry.key, entry.value);
      } finally {
        releasePageWriteLock(newBuddyIndex, newBuddyLevel, newBuddyPageLock.cacheLock);
      }

      final long bucketIndex = getPageIndex(hashTree[nodePath.nodeIndex][nodePath.itemIndex + nodePath.hashMapOffset]);
      final long newBuddyPointer = createBucketPointer(buddyIndex, buddyLevel);

      for (int i = firstStartIndex; i < secondEndIndex; i++)
        updateBucket(currentNode.nodeIndex, i, currentNode.hashMapOffset, newBuddyPointer);

      final OHashIndexBufferMetadata oldBuddyFileMetadata = filesMetadata[buddyLevel];
      if (oldBuddyFileMetadata.getBucketsCount() > 0) {
        final long newTombstoneIndex;
        if (bucketIndex < buddyIndex) {
          bucket.setNextRemovedBucketPair(oldBuddyFileMetadata.getTombstoneIndex());
          newTombstoneIndex = bucketIndex;
        } else {
          buddyBucket.setNextRemovedBucketPair(oldBuddyFileMetadata.getTombstoneIndex());
          newTombstoneIndex = buddyIndex;
        }

        oldBuddyFileMetadata.setTombstoneIndex(newTombstoneIndex);
      } else
        oldBuddyFileMetadata.setTombstoneIndex(-1);

      return true;
    } finally {
      releasePageWriteLock(buddyIndex, buddyLevel, buddyPageLock.cacheLock);
    }
  }

  public void flush() {
    acquireExclusiveLock();
    try {
      flushSplitBucketsBuffer();

      for (int i = 0; i < filesMetadata.length; i++)
        if (filesMetadata[i] != null)
          buffer.flushFile(name + i, bucketFileExtension);

    } catch (IOException e) {
      throw new OIndexException("Error during hash table flush", e);
    } finally {
      releaseExclusiveLock();
    }
  }

  private void doPut(K key, V value) {
    acquireExclusiveLock();
    try {
      final long hashCode = keyHashFunction.hashCode(key);

      final BucketPath bucketPath = getBucket(hashCode);
      long[] node = hashTree[bucketPath.nodeIndex];

      final long bucketPointer = node[bucketPath.itemIndex + bucketPath.hashMapOffset];
      if (bucketPointer == 0)
        throw new IllegalStateException("In this version of hash table buckets are added through split only.");

      final long pageIndex = getPageIndex(bucketPointer);
      final int fileLevel = getFileLevel(bucketPointer);

      PageLockResult pageLockResult = lockPageForWrite(pageIndex, fileLevel);

      try {
        final OHashIndexBucket<K, V> bucket = new OHashIndexBucket<K, V>(pageLockResult.dataPointer, directMemory, keySerializer,
            valueSerializer);
        final int index = bucket.getIndex(key);
        if (index > -1) {
          bucket.updateEntry(index, value);
          return;
        }

        if (bucket.addEntry(key, value)) {
          assert bucket.getEntry(bucket.getIndex(key)).value.equals(value);
          size++;
          return;
        }

        final BucketSplitResult splitResult = splitBucket(bucket, fileLevel, pageIndex, pageLockResult);

        final long updatedBucketPointer = splitResult.updatedBucketPointer;
        final long newBucketPointer = splitResult.newBucketPointer;
        final int bucketDepth = splitResult.newDepth;

        if (bucketDepth <= bucketPath.nodeGlobalDepth) {
          updateNodeAfterBucketSplit(bucketPath, bucketDepth, newBucketPointer, updatedBucketPointer);
        } else {
          if (bucketPath.nodeLocalDepth < MAX_LEVEL_DEPTH) {
            final NodeSplitResult nodeSplitResult = splitNode(bucketPath, node);

            assert !(nodeSplitResult.allLeftHashMapsEqual && nodeSplitResult.allRightHashMapsEqual);

            final long[] newNode = nodeSplitResult.newNode;

            final int nodeLocalDepth = bucketPath.nodeLocalDepth + 1;
            final int hashMapSize = 1 << nodeLocalDepth;

            assert nodeSplitResult.allRightHashMapsEqual == checkAllMapsContainSameBucket(newNode, hashMapSize);

            int newNodeIndex = -1;
            if (!nodeSplitResult.allRightHashMapsEqual || bucketPath.itemIndex >= MAX_LEVEL_SIZE / 2)
              newNodeIndex = addNewNode(newNode, nodeLocalDepth);

            final int updatedItemIndex = bucketPath.itemIndex << 1;
            final int updatedOffset = bucketPath.hashMapOffset << 1;
            final int updatedGlobalDepth = bucketPath.nodeGlobalDepth + 1;

            boolean allLeftHashMapsEqual = nodeSplitResult.allLeftHashMapsEqual;
            boolean allRightHashMapsEqual = nodeSplitResult.allRightHashMapsEqual;

            if (updatedOffset < MAX_LEVEL_SIZE) {
              allLeftHashMapsEqual = false;
              final BucketPath updatedBucketPath = new BucketPath(bucketPath.parent, updatedOffset, updatedItemIndex,
                  bucketPath.nodeIndex, nodeLocalDepth, updatedGlobalDepth);
              updateNodeAfterBucketSplit(updatedBucketPath, bucketDepth, newBucketPointer, updatedBucketPointer);
            } else {
              allRightHashMapsEqual = false;
              final BucketPath newBucketPath = new BucketPath(bucketPath.parent, updatedOffset - MAX_LEVEL_SIZE, updatedItemIndex,
                  newNodeIndex, nodeLocalDepth, updatedGlobalDepth);
              updateNodeAfterBucketSplit(newBucketPath, bucketDepth, newBucketPointer, updatedBucketPointer);
            }

            final long[] updatedNode = hashTree[bucketPath.nodeIndex];
            updateNodesAfterSplit(bucketPath, updatedNode, newNode, nodeLocalDepth, hashMapSize, allLeftHashMapsEqual,
                allRightHashMapsEqual, newNodeIndex);

            if (allLeftHashMapsEqual)
              deleteNode(bucketPath.nodeIndex);

          } else {
            addNewLevelNode(bucketPath, node, newBucketPointer, updatedBucketPointer);
          }
        }
      } finally {
        releasePageWriteLock(pageIndex, fileLevel, pageLockResult.cacheLock);
      }

      doPut(key, value);
    } catch (OIndexMaximumLimitReachedException e) {
      OLogManager.instance().warn(this, "Key " + key + " is too large to fit in index and will be skipped", e);
    } catch (IOException e) {
      throw new OIndexException("Error during index update", e);
    } finally {
      releaseExclusiveLock();
    }
  }

  private void updateNodesAfterSplit(BucketPath bucketPath, long[] node, long[] newNode, int nodeLocalDepth, int hashMapSize,
      boolean allLeftHashMapEquals, boolean allRightHashMapsEquals, int newNodeIndex) {

    final int startIndex = findParentNodeStartIndex(bucketPath);

    final long[] parentNode = hashTree[bucketPath.parent.nodeIndex];
    assert assertParentNodeStartIndex(bucketPath, parentNode, startIndex);

    final int pointersSize = 1 << (MAX_LEVEL_DEPTH - nodeLocalDepth);
    if (allLeftHashMapEquals) {
      for (int i = 0; i < pointersSize; i++) {
        final long position = node[i * hashMapSize];
        parentNode[startIndex + i] = position;
      }
    } else {
      for (int i = 0; i < pointersSize; i++)
        parentNode[startIndex + i] = (bucketPath.nodeIndex << 8) | (i * hashMapSize) | Long.MIN_VALUE;
    }

    if (allRightHashMapsEquals) {
      for (int i = 0; i < pointersSize; i++) {
        final long position = newNode[i * hashMapSize];
        parentNode[startIndex + pointersSize + i] = position;
      }
    } else {
      for (int i = 0; i < pointersSize; i++)
        parentNode[startIndex + pointersSize + i] = (newNodeIndex << 8) | (i * hashMapSize) | Long.MIN_VALUE;
    }

    updateMaxChildDepth(bucketPath.parent, bucketPath.nodeLocalDepth + 1);
  }

  private void updateMaxChildDepth(BucketPath parentPath, int childDepth) {
    if (parentPath == null)
      return;

    final OEHNodeMetadata metadata = nodesMetadata[parentPath.nodeIndex];
    if (parentPath.itemIndex < MAX_LEVEL_SIZE / 2) {
      final int maxChildDepth = metadata.getMaxLeftChildDepth();
      if (childDepth > maxChildDepth)
        metadata.setMaxLeftChildDepth(childDepth);
    } else {
      final int maxChildDepth = metadata.getMaxRightChildDepth();
      if (childDepth + 1 > maxChildDepth)
        metadata.setMaxRightChildDepth(childDepth);
    }
  }

  private boolean assertParentNodeStartIndex(BucketPath bucketPath, long[] parentNode, int calculatedIndex) {
    int startIndex = -1;
    for (int i = 0; i < parentNode.length; i++)
      if (parentNode[i] < 0 && (parentNode[i] & Long.MAX_VALUE) >>> 8 == bucketPath.nodeIndex) {
        startIndex = i;
        break;
      }

    return startIndex == calculatedIndex;
  }

  private int findParentNodeStartIndex(BucketPath bucketPath) {
    final BucketPath parentBucketPath = bucketPath.parent;
    final int pointersSize = 1 << (MAX_LEVEL_DEPTH - bucketPath.nodeLocalDepth);

    if (parentBucketPath.itemIndex < MAX_LEVEL_SIZE / 2)
      return (parentBucketPath.itemIndex / pointersSize) * pointersSize;

    return ((parentBucketPath.itemIndex - MAX_LEVEL_SIZE / 2) / pointersSize) * pointersSize + MAX_LEVEL_SIZE / 2;
  }

  private void addNewLevelNode(BucketPath bucketPath, long[] node, long newBucketPointer, long updatedBucketPointer) {
    final long[] newNode = new long[MAX_LEVEL_SIZE];

    final int newNodeDepth;
    final int newNodeStartIndex;
    final int mapInterval;

    if (bucketPath.itemIndex < node.length / 2) {
      final int maxDepth = nodesMetadata[bucketPath.nodeIndex].getMaxLeftChildDepth();
      assert getMaxLevelDepth(node, 0, node.length / 2) == maxDepth;
      if (maxDepth > 0)
        newNodeDepth = maxDepth;
      else
        newNodeDepth = 1;

      mapInterval = 1 << (MAX_LEVEL_DEPTH - newNodeDepth);
      newNodeStartIndex = (bucketPath.itemIndex / mapInterval) * mapInterval;
    } else {
      final int maxDepth = nodesMetadata[bucketPath.nodeIndex].getMaxRightChildDepth();
      assert getMaxLevelDepth(node, node.length / 2, node.length) == maxDepth;
      if (maxDepth > 0)
        newNodeDepth = maxDepth;
      else
        newNodeDepth = 1;

      mapInterval = 1 << (MAX_LEVEL_DEPTH - newNodeDepth);
      newNodeStartIndex = ((bucketPath.itemIndex - node.length / 2) / mapInterval) * mapInterval + node.length / 2;
    }

    final int newNodeIndex = addNewNode(newNode, newNodeDepth);
    final int mapSize = 1 << newNodeDepth;
    for (int i = 0; i < mapInterval; i++) {
      final int nodeOffset = i + newNodeStartIndex;
      final long bucketPointer = node[nodeOffset];

      if (nodeOffset != bucketPath.itemIndex) {
        for (int n = i << newNodeDepth; n < (i + 1) << newNodeDepth; n++)
          newNode[n] = bucketPointer;
      } else {
        for (int n = i << newNodeDepth; n < (2 * i + 1) << (newNodeDepth - 1); n++)
          newNode[n] = updatedBucketPointer;

        for (int n = (2 * i + 1) << (newNodeDepth - 1); n < (i + 1) << newNodeDepth; n++)
          newNode[n] = newBucketPointer;
      }

      node[nodeOffset] = (newNodeIndex << 8) | (i * mapSize) | Long.MIN_VALUE;
    }

    updateMaxChildDepth(bucketPath, newNodeDepth);
  }

  private int getMaxLevelDepth(long node[], int start, int end) {
    int currentIndex = -1;
    int maxDepth = 0;

    for (int i = start; i < end; i++) {
      final long nodePosition = node[i];
      if (nodePosition >= 0)
        continue;

      final int index = (int) ((nodePosition & Long.MAX_VALUE) >>> 8);
      if (index == currentIndex)
        continue;

      currentIndex = index;
      if (maxDepth < nodesMetadata[index].getNodeLocalDepth())
        maxDepth = nodesMetadata[index].getNodeLocalDepth();
    }

    return maxDepth;
  }

  private void updateNodeAfterBucketSplit(BucketPath bucketPath, int bucketDepth, long newBucketPointer, long updatedBucketPointer) {
    int offset = bucketPath.nodeGlobalDepth - (bucketDepth - 1);
    BucketPath currentNode = bucketPath;
    int nodeLocalDepth = bucketPath.nodeLocalDepth;
    while (offset > 0) {
      offset -= nodeLocalDepth;
      if (offset > 0) {
        currentNode = bucketPath.parent;
        nodeLocalDepth = currentNode.nodeLocalDepth;
      }
    }

    final int diff = bucketDepth - 1 - (currentNode.nodeGlobalDepth - nodeLocalDepth);

    final int interval = (1 << (nodeLocalDepth - diff - 1));
    final int firstStartIndex = currentNode.itemIndex & ((LEVEL_MASK << (nodeLocalDepth - diff)) & LEVEL_MASK);
    final int firstEndIndex = firstStartIndex + interval;

    final int secondStartIndex = firstEndIndex;
    final int secondEndIndex = secondStartIndex + interval;

    for (int i = firstStartIndex; i < firstEndIndex; i++)
      updateBucket(currentNode.nodeIndex, i, currentNode.hashMapOffset, updatedBucketPointer);

    for (int i = secondStartIndex; i < secondEndIndex; i++)
      updateBucket(currentNode.nodeIndex, i, currentNode.hashMapOffset, newBucketPointer);
  }

  private int addNewNode(long[] newNode, int nodeLocalDepth) {
    if (hashTreeTombstone >= 0) {
      long[] tombstone = hashTree[hashTreeTombstone];

      hashTree[hashTreeTombstone] = newNode;
      nodesMetadata[hashTreeTombstone] = new OEHNodeMetadata((byte) 0, (byte) 0, (byte) nodeLocalDepth);

      final int nodeIndex = hashTreeTombstone;
      if (tombstone != null)
        hashTreeTombstone = (int) tombstone[0];
      else
        hashTreeTombstone = -1;

      return nodeIndex;
    }

    if (hashTreeSize >= hashTree.length) {
      long[][] newHashTree = new long[hashTree.length << 1][];
      System.arraycopy(hashTree, 0, newHashTree, 0, hashTree.length);
      hashTree = newHashTree;
      newHashTree = null;

      OEHNodeMetadata[] newNodeMetadata = new OEHNodeMetadata[nodesMetadata.length << 1];
      System.arraycopy(nodesMetadata, 0, newNodeMetadata, 0, nodesMetadata.length);
      nodesMetadata = newNodeMetadata;
      newNodeMetadata = null;
    }

    hashTree[hashTreeSize] = newNode;
    nodesMetadata[hashTreeSize] = new OEHNodeMetadata((byte) 0, (byte) 0, (byte) nodeLocalDepth);

    hashTreeSize++;

    return hashTreeSize - 1;
  }

  private boolean checkAllMapsContainSameBucket(long[] newNode, int hashMapSize) {
    int n = 0;
    boolean allHashMapsEquals = true;
    while (n < newNode.length) {
      boolean allHashBucketEquals = true;
      for (int i = 0; i < hashMapSize - 1; i++) {
        if (newNode[i + n] != newNode[i + n + 1]) {
          allHashBucketEquals = false;
          break;
        }
      }
      n += hashMapSize;
      if (!allHashBucketEquals) {
        allHashMapsEquals = false;
        break;
      }
    }

    assert assertAllNodesAreFilePointers(allHashMapsEquals, newNode, hashMapSize);

    return allHashMapsEquals;
  }

  private boolean assertAllNodesAreFilePointers(boolean allHashMapsEquals, long[] newNode, int hashMapSize) {
    if (allHashMapsEquals) {
      int n = 0;
      while (n < newNode.length) {
        for (int i = 0; i < hashMapSize; i++) {
          if (newNode[i] < 0) {
            return false;
          }
        }
        n += hashMapSize;
      }
    }

    return true;
  }

  private NodeSplitResult splitNode(BucketPath bucketPath, long[] node) {
    final long[] newNode = new long[MAX_LEVEL_SIZE];
    final int hashMapSize = 1 << (bucketPath.nodeLocalDepth + 1);

    boolean hashMapItemsAreEqual = true;
    boolean allLeftItemsAreEqual;
    boolean allRightItemsAreEqual;

    int mapCounter = 0;
    long firstPosition = -1;

    for (int i = MAX_LEVEL_SIZE / 2; i < MAX_LEVEL_SIZE; i++) {
      final long position = node[i];
      if (hashMapItemsAreEqual && mapCounter == 0)
        firstPosition = position;

      newNode[2 * (i - MAX_LEVEL_SIZE / 2)] = position;
      newNode[2 * (i - MAX_LEVEL_SIZE / 2) + 1] = position;

      if (hashMapItemsAreEqual) {
        hashMapItemsAreEqual = firstPosition == position;
        mapCounter += 2;

        if (mapCounter >= hashMapSize)
          mapCounter = 0;
      }
    }

    mapCounter = 0;
    allRightItemsAreEqual = hashMapItemsAreEqual;

    hashMapItemsAreEqual = true;
    final long[] updatedNode = new long[node.length];
    for (int i = 0; i < MAX_LEVEL_SIZE / 2; i++) {
      final long position = node[i];
      if (hashMapItemsAreEqual && mapCounter == 0)
        firstPosition = position;

      updatedNode[2 * i] = position;
      updatedNode[2 * i + 1] = position;

      if (hashMapItemsAreEqual) {
        hashMapItemsAreEqual = firstPosition == position;

        mapCounter += 2;

        if (mapCounter >= hashMapSize)
          mapCounter = 0;
      }
    }

    allLeftItemsAreEqual = hashMapItemsAreEqual;

    nodesMetadata[bucketPath.nodeIndex].incrementLocalNodeDepth();
    hashTree[bucketPath.nodeIndex] = updatedNode;

    return new NodeSplitResult(newNode, allLeftItemsAreEqual, allRightItemsAreEqual);
  }

  private void deleteNode(int nodeIndex) {
    if (nodeIndex == hashTreeSize - 1) {
      hashTree[nodeIndex] = null;
      nodesMetadata[nodeIndex] = null;
      hashTreeSize--;
      return;
    }

    if (hashTreeTombstone > -1) {
      final long[] tombstone = new long[] { hashTreeTombstone };
      hashTree[nodeIndex] = tombstone;
      hashTreeTombstone = nodeIndex;
    } else {
      hashTree[nodeIndex] = null;
      hashTreeTombstone = nodeIndex;
    }

    nodesMetadata[nodeIndex] = null;
  }

  private void splitBucketContent(OHashIndexBucket<K, V> bucket, OHashIndexBucket<K, V> updatedBucket,
      OHashIndexBucket<K, V> newBucket, int newBucketDepth) {
    assert checkBucketDepth(bucket);

    for (OHashIndexBucket.Entry<K, V> entry : bucket) {
      if (((keyHashFunction.hashCode(entry.key) >>> (64 - newBucketDepth)) & 1) == 0)
        updatedBucket.appendEntry(entry.key, entry.value);
      else
        newBucket.appendEntry(entry.key, entry.value);
    }

    updatedBucket.setDepth(newBucketDepth);
    newBucket.setDepth(newBucketDepth);

    assert checkBucketDepth(updatedBucket);
    assert checkBucketDepth(newBucket);
  }

  private BucketSplitResult splitBucket(OHashIndexBucket<K, V> bucket, int fileLevel, long pageIndex, PageLockResult pageLockResult)
      throws IOException {
    int bucketDepth = bucket.getDepth();
    int newBucketDepth = bucketDepth + 1;

    int newFileLevel = newBucketDepth - MAX_LEVEL_DEPTH;
    OHashIndexBufferMetadata newFileMetadata = filesMetadata[newFileLevel];
    if (newFileMetadata == null) {
      newFileMetadata = createFileMetadata(newFileLevel);
      filesMetadata[newFileLevel] = newFileMetadata;
    }

    final long tombstoneIndex = newFileMetadata.getTombstoneIndex();

    final long updatedBucketIndex;
    if (tombstoneIndex >= 0) {
      final PageLockResult tombstonePageLockResult = lockPageForRead(tombstoneIndex, newFileLevel);
      try {
        final OHashIndexBucket<K, V> tombstone = new OHashIndexBucket<K, V>(tombstonePageLockResult.dataPointer, directMemory,
            keySerializer, valueSerializer);
        newFileMetadata.setTombstoneIndex(tombstone.getNextRemovedBucketPair());

        updatedBucketIndex = tombstoneIndex;
      } finally {
        releasePageReadLock(tombstoneIndex, newFileLevel, tombstonePageLockResult.cacheLock);
      }
    } else
      updatedBucketIndex = buffer.getFilledUpTo(name + newFileLevel, bucketFileExtension);

    final long newBucketIndex = updatedBucketIndex + 1;

    final long updatedBucketDataPointer = buffer.allocateAndLockForWrite(name + newFileLevel, bucketFileExtension,
        updatedBucketIndex);
    try {
      final long newBucketDataPointer = buffer.allocateAndLockForWrite(name + newFileLevel, bucketFileExtension, newBucketIndex);
      try {
        final OHashIndexBucket<K, V> updatedBucket = new OHashIndexBucket<K, V>(newBucketDepth, updatedBucketDataPointer,
            directMemory, keySerializer, valueSerializer);
        final OHashIndexBucket<K, V> newBucket = new OHashIndexBucket<K, V>(newBucketDepth, newBucketDataPointer, directMemory,
            keySerializer, valueSerializer);

        splitBucketContent(bucket, updatedBucket, newBucket, newBucketDepth);

        assert bucket.getDepth() == bucketDepth;

        final OHashIndexBufferMetadata bufferMetadata = filesMetadata[fileLevel];
        bufferMetadata.setBucketsCount(bufferMetadata.getBucketsCount() - 1);

        assert bufferMetadata.getBucketsCount() >= 0;

        updatedBucket.setSplitHistory(fileLevel, pageIndex);
        newBucket.setSplitHistory(fileLevel, pageIndex);

        newFileMetadata.setBucketsCount(newFileMetadata.getBucketsCount() + 2);

        final long updatedBucketPointer = createBucketPointer(updatedBucketIndex, newFileLevel);
        final long newBucketPointer = createBucketPointer(newBucketIndex, newFileLevel);

        SortedMap<Long, Long> splitBuckets = splitBucketsByLevels[newFileLevel];
        if (splitBuckets == null) {
          splitBuckets = new TreeMap<Long, Long>();
          splitBucketsByLevels[newFileLevel] = splitBuckets;
        }

        Long prevData = splitBuckets.put(updatedBucketIndex, updatedBucketDataPointer);
        assert prevData == null;

        prevData = splitBuckets.put(newBucketIndex, newBucketDataPointer);
        assert prevData == null;

        splitBucketsSize += 2;

        clearDirtyFlag(pageIndex, fileLevel, pageLockResult.cacheLock);

        flushSplitBucketsBuffer();

        return new BucketSplitResult(updatedBucketPointer, newBucketPointer, newBucketDepth);
      } finally {
        buffer.releaseWriteLock(name + newFileLevel, bucketFileExtension, newBucketIndex);
      }
    } finally {
      buffer.releaseWriteLock(name + newFileLevel, bucketFileExtension, updatedBucketIndex);
    }
  }

  private void flushSplitBucketsBuffer() throws IOException {
    if (splitBucketsSize > 1500) {
      for (int i = 0; i < splitBucketsByLevels.length; i++) {
        SortedMap<Long, Long> buckets = splitBucketsByLevels[i];
        if (buckets == null)
          continue;

        for (Map.Entry<Long, Long> splitBucketEntry : buckets.entrySet()) {
          long bucketPageIndex = splitBucketEntry.getKey();
          long dataPointer = splitBucketEntry.getValue();

          long bufferPointer = buffer.getAndLockForWrite(name + i, bucketFileExtension, bucketPageIndex);
          try {
            assert bufferPointer == ODirectMemory.NULL_POINTER || bufferPointer == dataPointer;

            buffer.flushData(name + i, bucketFileExtension, bucketPageIndex, dataPointer);
            if (bufferPointer != ODirectMemory.NULL_POINTER)
              buffer.clearExternalManagementFlag(name + i, bucketFileExtension, bucketPageIndex);
            else
              directMemory.free(dataPointer);
          } finally {
            buffer.releaseWriteLock(name + i, bucketFileExtension, bucketPageIndex);
          }
        }

        buckets.clear();
      }

      splitBucketsSize = 0;
    }
  }

  private boolean checkBucketDepth(OHashIndexBucket<K, V> bucket) {
    int bucketDepth = bucket.getDepth();

    if (bucket.size() == 0)
      return true;

    final Iterator<OHashIndexBucket.Entry<K, V>> positionIterator = bucket.iterator();

    long firstValue = keyHashFunction.hashCode(positionIterator.next().key) >>> (64 - bucketDepth);
    while (positionIterator.hasNext()) {
      final long value = keyHashFunction.hashCode(positionIterator.next().key) >>> (64 - bucketDepth);
      if (value != firstValue)
        return false;
    }

    return true;
  }

  private void updateBucket(int nodeIndex, int itemIndex, int offset, long newBucketPointer) {
    final long node[] = hashTree[nodeIndex];

    final long position = node[itemIndex + offset];
    if (position >= 0)
      node[itemIndex + offset] = newBucketPointer;
    else {
      final int childNodeIndex = (int) ((position & Long.MAX_VALUE) >>> 8);
      final int childOffset = (int) (position & 0xFF);
      final int childNodeDepth = nodesMetadata[childNodeIndex].getNodeLocalDepth();
      final int interval = 1 << childNodeDepth;
      for (int i = 0; i < interval; i++) {
        updateBucket(childNodeIndex, i, childOffset, newBucketPointer);
      }
    }
  }

  private void initHashTreeState() throws IOException {
    for (long pageIndex = 0; pageIndex < MAX_LEVEL_SIZE; pageIndex++) {
      final PageLockResult pageLockResult = lockPageForWrite(pageIndex, 0);
      try {
        final OHashIndexBucket<K, V> emptyBucket = new OHashIndexBucket<K, V>(MAX_LEVEL_DEPTH, pageLockResult.dataPointer,
            directMemory, keySerializer, valueSerializer);
      } finally {
        releasePageWriteLock(pageIndex, 0, pageLockResult.cacheLock);
      }
    }

    final long[] rootTree = new long[MAX_LEVEL_SIZE];
    for (int i = 0; i < MAX_LEVEL_SIZE; i++)
      rootTree[i] = createBucketPointer(i, 0);

    hashTree = new long[1][];
    hashTree[0] = rootTree;

    nodesMetadata = new OEHNodeMetadata[1];
    nodesMetadata[0] = new OEHNodeMetadata((byte) 0, (byte) 0, (byte) MAX_LEVEL_DEPTH);

    filesMetadata[0].setBucketsCount(MAX_LEVEL_SIZE);

    size = 0;
    hashTreeSize = 1;
  }

  private long createBucketPointer(long pageIndex, int fileLevel) {
    return ((pageIndex + 1) << 8) | fileLevel;
  }

  private long getPageIndex(long bucketPointer) {
    return (bucketPointer >>> 8) - 1;
  }

  private int getFileLevel(long bucketPointer) {
    return (int) (bucketPointer & 0xFF);
  }

  private PageLockResult lockPageForRead(long pageIndex, int fileLevel) throws IOException {
    Long dataPointer = null;
    boolean cacheLock = false;
    if (splitBucketsByLevels[fileLevel] != null) {
      dataPointer = splitBucketsByLevels[fileLevel].get(pageIndex);
      if (dataPointer != null)
        buffer.cacheHit(name + fileLevel, bucketFileExtension, pageIndex, dataPointer);
    }
    if (dataPointer == null) {
      dataPointer = buffer.loadAndLockForRead(name + fileLevel, bucketFileExtension, pageIndex);
      cacheLock = true;
    }

    return new PageLockResult(dataPointer, cacheLock);
  }

  private void releasePageReadLock(long pageIndex, int fileLevel, boolean cacheLock) {
    if (cacheLock)
      buffer.releaseReadLock(name + fileLevel, bucketFileExtension, pageIndex);
  }

  private PageLockResult lockPageForWrite(long pageIndex, int fileLevel) throws IOException {
    Long dataPointer = null;
    boolean cacheLock = false;
    if (splitBucketsByLevels[fileLevel] != null) {
      dataPointer = splitBucketsByLevels[fileLevel].get(pageIndex);
      if (dataPointer != null)
        buffer.cacheHit(name + fileLevel, bucketFileExtension, pageIndex, dataPointer);
    }
    if (dataPointer == null) {
      dataPointer = buffer.loadAndLockForWrite(name + fileLevel, bucketFileExtension, pageIndex);
      cacheLock = true;
    }

    return new PageLockResult(dataPointer, cacheLock);
  }

  private void releasePageWriteLock(long pageIndex, int fileLevel, boolean cacheLock) {
    if (cacheLock)
      buffer.releaseWriteLock(name + fileLevel, bucketFileExtension, pageIndex);
  }

  private void clearDirtyFlag(long pageIndex, int fileLevel, boolean cacheLock) {
    if (cacheLock)
      buffer.clearDirtyFlag(name + fileLevel, bucketFileExtension, pageIndex);
    else {
      Long dataPointer = splitBucketsByLevels[fileLevel].remove(pageIndex);
      long bufferPointer = buffer.getAndLockForWrite(name + fileLevel, bucketFileExtension, pageIndex);
      try {
        assert bufferPointer == ODirectMemory.NULL_POINTER || bufferPointer == dataPointer;

        if (bufferPointer != ODirectMemory.NULL_POINTER)
          directMemory.free(dataPointer);
        else
          buffer.clearExternalManagementFlag(name + fileLevel, bucketFileExtension, pageIndex);
      } finally {
        buffer.releaseWriteLock(name + fileLevel, bucketFileExtension, pageIndex);
      }
    }
  }

  private BucketPath getBucket(final long hashCode) {
    int localNodeDepth = nodesMetadata[0].getNodeLocalDepth();
    int nodeDepth = localNodeDepth;
    BucketPath parentNode = null;
    int nodeIndex = 0;
    int offset = 0;

    int index = (int) ((hashCode >>> (64 - nodeDepth)) & (LEVEL_MASK >>> (MAX_LEVEL_DEPTH - localNodeDepth)));
    BucketPath currentNode = new BucketPath(parentNode, 0, index, 0, localNodeDepth, nodeDepth);
    do {
      final long position = hashTree[nodeIndex][index + offset];
      if (position >= 0)
        return currentNode;

      nodeIndex = (int) ((position & Long.MAX_VALUE) >>> 8);
      offset = (int) (position & 0xFF);

      localNodeDepth = nodesMetadata[nodeIndex].getNodeLocalDepth();
      nodeDepth += localNodeDepth;

      index = (int) ((hashCode >>> (64 - nodeDepth)) & (LEVEL_MASK >>> (MAX_LEVEL_DEPTH - localNodeDepth)));

      parentNode = currentNode;
      currentNode = new BucketPath(parentNode, offset, index, nodeIndex, localNodeDepth, nodeDepth);
    } while (nodeDepth <= 64);

    throw new IllegalStateException("Extendible hashing tree in corrupted state.");
  }

  private static final class BucketPath {
    private final BucketPath parent;
    private final int        hashMapOffset;
    private final int        itemIndex;
    private final int        nodeIndex;
    private final int        nodeGlobalDepth;
    private final int        nodeLocalDepth;

    private BucketPath(BucketPath parent, int hashMapOffset, int itemIndex, int nodeIndex, int nodeLocalDepth, int nodeGlobalDepth) {
      this.parent = parent;
      this.hashMapOffset = hashMapOffset;
      this.itemIndex = itemIndex;
      this.nodeIndex = nodeIndex;
      this.nodeGlobalDepth = nodeGlobalDepth;
      this.nodeLocalDepth = nodeLocalDepth;
    }
  }

  private static final class BucketSplitResult {
    private final long updatedBucketPointer;
    private final long newBucketPointer;
    private final int  newDepth;

    private BucketSplitResult(long updatedBucketPointer, long newBucketPointer, int newDepth) {
      this.updatedBucketPointer = updatedBucketPointer;
      this.newBucketPointer = newBucketPointer;
      this.newDepth = newDepth;
    }
  }

  private static final class NodeSplitResult {
    private final long[]  newNode;
    private final boolean allLeftHashMapsEqual;
    private final boolean allRightHashMapsEqual;

    private NodeSplitResult(long[] newNode, boolean allLeftHashMapsEqual, boolean allRightHashMapsEqual) {
      this.newNode = newNode;
      this.allLeftHashMapsEqual = allLeftHashMapsEqual;
      this.allRightHashMapsEqual = allRightHashMapsEqual;
    }
  }

  private static final class PageLockResult {
    private final long    dataPointer;
    private final boolean cacheLock;

    private PageLockResult(long dataPointer, boolean cacheLock) {
      this.dataPointer = dataPointer;
      this.cacheLock = cacheLock;
    }
  }
}
