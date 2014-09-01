package com.orientechnologies.common.concur.lock;

import com.orientechnologies.common.types.OModifiableInteger;

import java.util.HashSet;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.AbstractOwnableSynchronizer;
import java.util.concurrent.locks.LockSupport;

/**
 * @author Andrey Lomakin <a href="mailto:lomakin.andrey@gmail.com">Andrey Lomakin</a>
 * @since 8/18/14
 */
public class OReadersWriterSpinLock extends AbstractOwnableSynchronizer {
  private final OThreadCountersHashTable        threadCountersHashTable = new OThreadCountersHashTable();

  private final AtomicReference<WNode>          tail                    = new AtomicReference<WNode>();
  private final ThreadLocal<OModifiableInteger> lockHolds               = new ThreadLocal<OModifiableInteger>() {
                                                                          @Override
                                                                          protected OModifiableInteger initialValue() {
                                                                            return new OModifiableInteger();
                                                                          }
                                                                        };

  private final ThreadLocal<WNode>              myNode                  = new ThreadLocal<WNode>() {
                                                                          @Override
                                                                          protected WNode initialValue() {
                                                                            return new WNode();
                                                                          }
                                                                        };
  private final ThreadLocal<WNode>              predNode                = new ThreadLocal<WNode>();

  public OReadersWriterSpinLock() {
    final WNode wNode = new WNode();
    wNode.locked = false;

    tail.set(wNode);
  }

  public void acquireReadLock() {
    final OModifiableInteger lHolds = lockHolds.get();

    final int holds = lHolds.intValue();
    if (holds > 0) {
      // we have already acquire read lock
      lHolds.increment();
      return;
    } else if (holds < 0) {
      // write lock is acquired before, do nothing
      return;
    }

    threadCountersHashTable.increment();

    WNode wNode = tail.get();
    while (wNode.locked) {
      threadCountersHashTable.decrement();

      while (wNode.locked) {
        wNode.waitingReaders.add(Thread.currentThread());

        if (wNode == tail.get() && wNode.locked)
          LockSupport.park(this);

        wNode = tail.get();
      }

      threadCountersHashTable.increment();

      wNode = tail.get();
    }

    lHolds.increment();
    assert lHolds.intValue() == 1;
  }

  public void releaseReadLock() {
    final OModifiableInteger lHolds = lockHolds.get();
    final int holds = lHolds.intValue();
    if (holds > 1) {
      lockHolds.get().decrement();
      return;
    } else if (holds < 0) {
      // write lock was acquired before, do nothing
      return;
    }

    threadCountersHashTable.decrement();

    lHolds.decrement();
    assert lHolds.intValue() == 0;
  }

  public void acquireWriteLock() {
    final OModifiableInteger lHolds = lockHolds.get();

    if (lHolds.intValue() < 0) {
      lHolds.decrement();
      return;
    }

    final WNode node = myNode.get();
    node.locked = true;

    final WNode pNode = tail.getAndSet(myNode.get());
    predNode.set(pNode);

    while (pNode.locked) {
      pNode.waitingWriter = Thread.currentThread();

      if (pNode.locked)
        LockSupport.park(this);
    }

    pNode.waitingWriter = null;

    while (!threadCountersHashTable.isEmpty())
      ;

    setExclusiveOwnerThread(Thread.currentThread());

    lHolds.decrement();
    assert lHolds.intValue() == -1;
  }

  public void releaseWriteLock() {
    final OModifiableInteger lHolds = lockHolds.get();

    if (lHolds.intValue() < -1) {
      lHolds.increment();
      return;
    }

    setExclusiveOwnerThread(null);

    final WNode node = myNode.get();
    node.locked = false;

    final Thread waitingWriter = node.waitingWriter;
    if (waitingWriter != null)
      LockSupport.unpark(waitingWriter);

    final Set<Thread> waitingReaders = new HashSet<Thread>();

    for (Thread waitingReader : node.waitingReaders) {
      if (waitingReaders.add(waitingReader))
        LockSupport.unpark(waitingReader);
    }

    myNode.set(predNode.get());
    predNode.set(null);

    lHolds.increment();
    assert lHolds.intValue() == 0;
  }

  private final class WNode {
    private final Queue<Thread> waitingReaders = new ConcurrentLinkedDeque<Thread>();

    private volatile boolean    locked         = true;
    private volatile Thread     waitingWriter;
  }
}