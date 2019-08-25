package io.netty.util.internal.shaded.org.jctools.queues;

import io.netty.util.internal.shaded.org.jctools.queues.MessagePassingQueue.Consumer;
import io.netty.util.internal.shaded.org.jctools.queues.MessagePassingQueue.ExitCondition;
import io.netty.util.internal.shaded.org.jctools.queues.MessagePassingQueue.Supplier;
import io.netty.util.internal.shaded.org.jctools.queues.MessagePassingQueue.WaitStrategy;
import io.netty.util.internal.shaded.org.jctools.util.PortableJvmInfo;
import io.netty.util.internal.shaded.org.jctools.util.Pow2;
import io.netty.util.internal.shaded.org.jctools.util.RangeUtil;
import io.netty.util.internal.shaded.org.jctools.util.UnsafeRefArrayAccess;
import java.util.Iterator;

public abstract class BaseMpscLinkedArrayQueue<E> extends BaseMpscLinkedArrayQueueColdProducerFields<E> implements MessagePassingQueue<E>, QueueProgressIndicators {
    private static final int CONTINUE_TO_P_INDEX_CAS = 0;
    private static final Object JUMP = new Object();
    private static final int QUEUE_FULL = 2;
    private static final int QUEUE_RESIZE = 3;
    private static final int RETRY = 1;

    protected abstract long availableInQueue(long j, long j2);

    public abstract int capacity();

    protected abstract long getCurrentBufferCapacity(long j);

    protected abstract int getNextBufferSize(E[] eArr);

    public BaseMpscLinkedArrayQueue(int initialCapacity) {
        RangeUtil.checkGreaterThanOrEqual(initialCapacity, 2, "initialCapacity");
        int p2capacity = Pow2.roundToPowerOfTwo(initialCapacity);
        long mask = (long) ((p2capacity - 1) << 1);
        E[] buffer = CircularArrayOffsetCalculator.allocate(p2capacity + 1);
        this.producerBuffer = buffer;
        this.producerMask = mask;
        this.consumerBuffer = buffer;
        this.consumerMask = mask;
        soProducerLimit(mask);
    }

    public final Iterator<E> iterator() {
        throw new UnsupportedOperationException();
    }

    public final int size() {
        long currentProducerIndex;
        long after = lvConsumerIndex();
        long before;
        do {
            before = after;
            currentProducerIndex = lvProducerIndex();
            after = lvConsumerIndex();
        } while (before != after);
        long size = (currentProducerIndex - after) >> 1;
        if (size > 2147483647L) {
            return Integer.MAX_VALUE;
        }
        return (int) size;
    }

    public final boolean isEmpty() {
        return lvConsumerIndex() == lvProducerIndex();
    }

    public String toString() {
        return getClass().getName();
    }

    public boolean offer(E e) {
        if (e == null) {
            throw new NullPointerException();
        }
        while (true) {
            long producerLimit = lvProducerLimit();
            long pIndex = lvProducerIndex();
            if ((1 & pIndex) != 1) {
                long mask = this.producerMask;
                E[] buffer = this.producerBuffer;
                if (producerLimit <= pIndex) {
                    switch (offerSlowPath(mask, pIndex, producerLimit)) {
                        case 1:
                            break;
                        case 2:
                            return false;
                        case 3:
                            resize(mask, buffer, pIndex, e);
                            return true;
                    }
                }
                if (casProducerIndex(pIndex, 2 + pIndex)) {
                    UnsafeRefArrayAccess.soElement(buffer, LinkedArrayQueueUtil.modifiedCalcElementOffset(pIndex, mask), e);
                    return true;
                }
            }
        }
    }

    public E poll() {
        E[] buffer = this.consumerBuffer;
        long index = this.consumerIndex;
        long mask = this.consumerMask;
        long offset = LinkedArrayQueueUtil.modifiedCalcElementOffset(index, mask);
        E e = UnsafeRefArrayAccess.lvElement(buffer, offset);
        if (e == null) {
            if (index == lvProducerIndex()) {
                return null;
            }
            do {
                e = UnsafeRefArrayAccess.lvElement(buffer, offset);
            } while (e == null);
        }
        if (e == JUMP) {
            return newBufferPoll(getNextBuffer(buffer, mask), index);
        }
        UnsafeRefArrayAccess.soElement(buffer, offset, null);
        soConsumerIndex(2 + index);
        return e;
    }

    public E peek() {
        E[] buffer = this.consumerBuffer;
        long index = this.consumerIndex;
        long mask = this.consumerMask;
        long offset = LinkedArrayQueueUtil.modifiedCalcElementOffset(index, mask);
        Object e = UnsafeRefArrayAccess.lvElement(buffer, offset);
        if (e == null && index != lvProducerIndex()) {
            do {
                e = UnsafeRefArrayAccess.lvElement(buffer, offset);
            } while (e == null);
            if (e != JUMP) {
                return (E) e;
            }
            return newBufferPeek(getNextBuffer(buffer, mask), index);
        } else if (e != JUMP) {
            return newBufferPeek(getNextBuffer(buffer, mask), index);
        } else {
            return (E) e;
        }
    }

    private int offerSlowPath(long mask, long pIndex, long producerLimit) {
        long cIndex = lvConsumerIndex();
        long bufferCapacity = getCurrentBufferCapacity(mask);
        if (cIndex + bufferCapacity > pIndex) {
            if (casProducerLimit(producerLimit, cIndex + bufferCapacity)) {
                return 0;
            }
            return 1;
        } else if (availableInQueue(pIndex, cIndex) <= 0) {
            return 2;
        } else {
            if (casProducerIndex(pIndex, 1 + pIndex)) {
                return 3;
            }
            return 1;
        }
    }

    private E[] getNextBuffer(E[] buffer, long mask) {
        long offset = nextArrayOffset(mask);
        E[] nextBuffer = (E[]) UnsafeRefArrayAccess.lvElement(buffer, offset);
        UnsafeRefArrayAccess.soElement(buffer, offset, null);
        return nextBuffer;
    }

    private long nextArrayOffset(long mask) {
        return LinkedArrayQueueUtil.modifiedCalcElementOffset(2 + mask, Long.MAX_VALUE);
    }

    private E newBufferPoll(E[] nextBuffer, long index) {
        long offset = newBufferAndOffset(nextBuffer, index);
        E n = UnsafeRefArrayAccess.lvElement(nextBuffer, offset);
        if (n == null) {
            throw new IllegalStateException("new buffer must have at least one element");
        }
        UnsafeRefArrayAccess.soElement(nextBuffer, offset, null);
        soConsumerIndex(2 + index);
        return n;
    }

    private E newBufferPeek(E[] nextBuffer, long index) {
        E n = UnsafeRefArrayAccess.lvElement(nextBuffer, newBufferAndOffset(nextBuffer, index));
        if (n != null) {
            return n;
        }
        throw new IllegalStateException("new buffer must have at least one element");
    }

    private long newBufferAndOffset(E[] nextBuffer, long index) {
        this.consumerBuffer = nextBuffer;
        this.consumerMask = (long) ((LinkedArrayQueueUtil.length(nextBuffer) - 2) << 1);
        return LinkedArrayQueueUtil.modifiedCalcElementOffset(index, this.consumerMask);
    }

    public long currentProducerIndex() {
        return lvProducerIndex() / 2;
    }

    public long currentConsumerIndex() {
        return lvConsumerIndex() / 2;
    }

    public boolean relaxedOffer(E e) {
        return offer(e);
    }

    public E relaxedPoll() {
        E[] buffer = this.consumerBuffer;
        long index = this.consumerIndex;
        long mask = this.consumerMask;
        long offset = LinkedArrayQueueUtil.modifiedCalcElementOffset(index, mask);
        Object e = UnsafeRefArrayAccess.lvElement(buffer, offset);
        if (e == null) {
            return null;
        }
        if (e == JUMP) {
            return newBufferPoll(getNextBuffer(buffer, mask), index);
        }
        UnsafeRefArrayAccess.soElement(buffer, offset, null);
        soConsumerIndex(2 + index);
        return (E) e;
    }

    public E relaxedPeek() {
        E[] buffer = this.consumerBuffer;
        long index = this.consumerIndex;
        long mask = this.consumerMask;
        Object e = UnsafeRefArrayAccess.lvElement(buffer, LinkedArrayQueueUtil.modifiedCalcElementOffset(index, mask));
        if (e == JUMP) {
            return newBufferPeek(getNextBuffer(buffer, mask), index);
        }
        return (E) e;
    }

    public int fill(Supplier<E> s) {
        long result = 0;
        int capacity = capacity();
        do {
            int filled = fill(s, PortableJvmInfo.RECOMENDED_OFFER_BATCH);
            if (filled == 0) {
                return (int) result;
            }
            result += (long) filled;
        } while (result <= ((long) capacity));
        return (int) result;
    }

    public int fill(Supplier<E> s, int batchSize) {
        while (true) {
            long producerLimit = lvProducerLimit();
            long pIndex = lvProducerIndex();
            if ((1 & pIndex) != 1) {
                long mask = this.producerMask;
                E[] buffer = this.producerBuffer;
                long batchIndex = Math.min(producerLimit, ((long) (batchSize * 2)) + pIndex);
                if (pIndex >= producerLimit || producerLimit < batchIndex) {
                    switch (offerSlowPath(mask, pIndex, producerLimit)) {
                        case 0:
                        case 1:
                            break;
                        case 2:
                            return 0;
                        case 3:
                            resize(mask, buffer, pIndex, s.get());
                            return 1;
                    }
                }
                if (casProducerIndex(pIndex, batchIndex)) {
                    int i = (int) ((batchIndex - pIndex) / 2);
                    for (int i2 = 0; i2 < i; i2++) {
                        UnsafeRefArrayAccess.soElement(buffer, LinkedArrayQueueUtil.modifiedCalcElementOffset(((long) (i2 * 2)) + pIndex, mask), s.get());
                    }
                    return i;
                }
            }
        }
    }

    public void fill(Supplier<E> s, WaitStrategy w, ExitCondition exit) {
        while (exit.keepRunning()) {
            if (fill(s, PortableJvmInfo.RECOMENDED_OFFER_BATCH) == 0) {
                int idleCounter = 0;
                while (exit.keepRunning() && fill(s, PortableJvmInfo.RECOMENDED_OFFER_BATCH) == 0) {
                    idleCounter = w.idle(idleCounter);
                }
            }
        }
    }

    public int drain(Consumer<E> c) {
        return drain(c, capacity());
    }

    public int drain(Consumer<E> c, int limit) {
        int i = 0;
        while (i < limit) {
            E m = relaxedPoll();
            if (m == null) {
                break;
            }
            c.accept(m);
            i++;
        }
        return i;
    }

    public void drain(Consumer<E> c, WaitStrategy w, ExitCondition exit) {
        int idleCounter = 0;
        while (exit.keepRunning()) {
            E e = relaxedPoll();
            if (e == null) {
                idleCounter = w.idle(idleCounter);
            } else {
                idleCounter = 0;
                c.accept(e);
            }
        }
    }

    private void resize(long oldMask, E[] oldBuffer, long pIndex, E e) {
        int newBufferLength = getNextBufferSize(oldBuffer);
        E[] newBuffer = CircularArrayOffsetCalculator.allocate(newBufferLength);
        this.producerBuffer = newBuffer;
        int newMask = (newBufferLength - 2) << 1;
        this.producerMask = (long) newMask;
        long offsetInOld = LinkedArrayQueueUtil.modifiedCalcElementOffset(pIndex, oldMask);
        UnsafeRefArrayAccess.soElement(newBuffer, LinkedArrayQueueUtil.modifiedCalcElementOffset(pIndex, (long) newMask), e);
        UnsafeRefArrayAccess.soElement(oldBuffer, nextArrayOffset(oldMask), newBuffer);
        long availableInQueue = availableInQueue(pIndex, lvConsumerIndex());
        RangeUtil.checkPositive(availableInQueue, "availableInQueue");
        soProducerLimit(Math.min((long) newMask, availableInQueue) + pIndex);
        soProducerIndex(2 + pIndex);
        UnsafeRefArrayAccess.soElement(oldBuffer, offsetInOld, JUMP);
    }
}
