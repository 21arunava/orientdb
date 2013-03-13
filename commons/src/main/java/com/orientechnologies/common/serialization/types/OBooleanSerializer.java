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

package com.orientechnologies.common.serialization.types;

import com.orientechnologies.common.directmemory.ODirectMemory;

/**
 * Serializer for boolean type .
 * 
 * @author ibershadskiy <a href="mailto:ibersh20@gmail.com">Ilya Bershadskiy</a>
 * @since 18.01.12
 */
public class OBooleanSerializer implements OBinarySerializer<Boolean> {
  /**
   * size of boolean value in bytes
   */
  public static final int          BOOLEAN_SIZE = 1;

  public static OBooleanSerializer INSTANCE     = new OBooleanSerializer();
  public static final byte         ID           = 1;

  public int getObjectSize(Boolean object) {
    return BOOLEAN_SIZE;
  }

  public void serialize(Boolean object, byte[] stream, int startPosition) {
    if (object)
      stream[startPosition] = (byte) 1;
    else
      stream[startPosition] = (byte) 0;
  }

  public Boolean deserialize(byte[] stream, int startPosition) {
    return stream[startPosition] == 1;
  }

  public int getObjectSize(byte[] stream, int startPosition) {
    return BOOLEAN_SIZE;
  }

  public byte getId() {
    return ID;
  }

  public int getObjectSizeNative(byte[] stream, int startPosition) {
    return BOOLEAN_SIZE;
  }

  public void serializeNative(Boolean object, byte[] stream, int startPosition) {
    serialize(object, stream, startPosition);
  }

  public Boolean deserializeNative(byte[] stream, int startPosition) {
    return deserialize(stream, startPosition);
  }

  @Override
  public void serializeInDirectMemory(Boolean object, ODirectMemory memory, long pointer) {
    memory.setByte(pointer, object ? (byte) 1 : 0);
  }

  @Override
  public Boolean deserializeFromDirectMemory(ODirectMemory memory, long pointer) {
    return memory.getByte(pointer) > 0;
  }

  @Override
  public int getObjectSizeInDirectMemory(ODirectMemory memory, long pointer) {
    return BOOLEAN_SIZE;
  }

  public boolean isFixedLength() {
    return true;
  }

  public int getFixedLength() {
    return BOOLEAN_SIZE;
  }
}
