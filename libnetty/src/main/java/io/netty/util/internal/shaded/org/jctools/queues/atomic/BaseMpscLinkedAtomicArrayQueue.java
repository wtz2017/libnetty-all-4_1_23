package io.netty.util.internal.shaded.org.jctools.queues.atomic;

import io.netty.util.internal.shaded.org.jctools.queues.MessagePassingQueue;
import io.netty.util.internal.shaded.org.jctools.queues.MessagePassingQueue.Consumer;
import io.netty.util.internal.shaded.org.jctools.queues.MessagePassingQueue.ExitCondition;
import io.netty.util.internal.shaded.org.jctools.queues.MessagePassingQueue.Supplier;
import io.netty.util.internal.shaded.org.jctools.queues.MessagePassingQueue.WaitStrategy;
import io.netty.util.internal.shaded.org.jctools.queues.QueueProgressIndicators;
import io.netty.util.internal.shaded.org.jctools.util.PortableJvmInfo;
import io.netty.util.internal.shaded.org.jctools.util.Pow2;
import io.netty.util.internal.shaded.org.jctools.util.RangeUtil;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicReferenceArray;

public abstract class BaseMpscLinkedAtomicArrayQueue<E> extends BaseMpscLinkedAtomicArrayQueueColdProducerFields<E> implements MessagePassingQueue<E>, QueueProgressIndicators {
    private static final Object JUMP = new Object();

    protected abstract long availableInQueue(long j, long j2);

    public abstract int capacity();

    protected abstract long getCurrentBufferCapacity(long j);

    protected abstract int getNextBufferSize(AtomicReferenceArray<E> atomicReferenceArray);

    public BaseMpscLinkedAtomicArrayQueue(int initialCapacity) {
        RangeUtil.checkGreaterThanOrEqual(initialCapacity, 2, "initialCapacity");
        int p2capacity = Pow2.roundToPowerOfTwo(initialCapacity);
        long mask = (long) ((p2capacity - 1) << 1);
        AtomicReferenceArray<E> buffer = LinkedAtomicArrayQueueUtil.allocate(p2capacity + 1);
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
                AtomicReferenceArray<E> buffer = this.producerBuffer;
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
                    LinkedAtomicArrayQueueUtil.soElement(buffer, LinkedAtomicArrayQueueUtil.modifiedCalcElementOffset(pIndex, mask), e);
                    return true;
                }
            }
        }
    }

    public E poll() {
        AtomicReferenceArray<E> buffer = this.consumerBuffer;
        long index = this.consumerIndex;
        long mask = this.consumerMask;
        int offset = LinkedAtomicArrayQueueUtil.modifiedCalcElementOffset(index, mask);
        E e = LinkedAtomicArrayQueueUtil.lvElement(buffer, offset);
        if (e == null) {
            if (index == lvProducerIndex()) {
                return null;
            }
            do {
                e = LinkedAtomicArrayQueueUtil.lvElement(buffer, offset);
            } while (e == null);
        }
        if (e == JUMP) {
            return newBufferPoll(getNextBuffer(buffer, mask), index);
        }
        LinkedAtomicArrayQueueUtil.soElement(buffer, offset, null);
        soConsumerIndex(2 + index);
        return e;
    }

    public E peek() {
        AtomicReferenceArray<E> buffer = this.consumerBuffer;
        long index = this.consumerIndex;
        long mask = this.consumerMask;
        int offset = LinkedAtomicArrayQueueUtil.modifiedCalcElementOffset(index, mask);
        Object e = LinkedAtomicArrayQueueUtil.lvElement(buffer, offset);
        if (e == null && index != lvProducerIndex()) {
            do {
                e = LinkedAtomicArrayQueueUtil.lvElement(buffer, offset);
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

    private AtomicReferenceArray<E> getNextBuffer(AtomicReferenceArray<E> buffer, long mask) {
        int offset = nextArrayOffset(mask);
        AtomicReferenceArray<E> nextBuffer = (AtomicReferenceArray) LinkedAtomicArrayQueueUtil.lvElement(buffer, offset);
        LinkedAtomicArrayQueueUtil.soElement(buffer, offset, null);
        return nextBuffer;
    }

    private int nextArrayOffset(long mask) {
        return LinkedAtomicArrayQueueUtil.modifiedCalcElementOffset(2 + mask, Long.MAX_VALUE);
    }

    private E newBufferPoll(AtomicReferenceArray<E> nextBuffer, long index) {
        int offset = newBufferAndOffset(nextBuffer, index);
        E n = LinkedAtomicArrayQueueUtil.lvElement(nextBuffer, offset);
        if (n == null) {
            throw new IllegalStateException("new buffer must have at least one element");
        }
        LinkedAtomicArrayQueueUtil.soElement(nextBuffer, offset, null);
        soConsumerIndex(2 + index);
        return n;
    }

    private E newBufferPeek(AtomicReferenceArray<E> nextBuffer, long index) {
        E n = LinkedAtomicArrayQueueUtil.lvElement(nextBuffer, newBufferAndOffset(nextBuffer, index));
        if (n != null) {
            return n;
        }
        throw new IllegalStateException("new buffer must have at least one element");
    }

    private int newBufferAndOffset(AtomicReferenceArray<E> nextBuffer, long index) {
        this.consumerBuffer = nextBuffer;
        this.consumerMask = (long) ((LinkedAtomicArrayQueueUtil.length(nextBuffer) - 2) << 1);
        return LinkedAtomicArrayQueueUtil.modifiedCalcElementOffset(index, this.consumerMask);
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
        AtomicReferenceArray<E> buffer = this.consumerBuffer;
        long index = this.consumerIndex;
        long mask = this.consumerMask;
        int offset = LinkedAtomicArrayQueueUtil.modifiedCalcElementOffset(index, mask);
        Object e = LinkedAtomicArrayQueueUtil.lvElement(buffer, offset);
        if (e == null) {
            return null;
        }
        if (e == JUMP) {
            return newBufferPoll(getNextBuffer(buffer, mask), index);
        }
        LinkedAtomicArrayQueueUtil.soElement(buffer, offset, null);
        soConsumerIndex(2 + index);
        return (E) e;
    }

    public E relaxedPeek() {
        AtomicReferenceArray<E> buffer = this.consumerBuffer;
        long index = this.consumerIndex;
        long mask = this.consumerMask;
        Object e = LinkedAtomicArrayQueueUtil.lvElement(buffer, LinkedAtomicArrayQueueUtil.modifiedCalcElementOffset(index, mask));
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
                AtomicReferenceArray<E> buffer = this.producerBuffer;
                long batchIndex = Math.min(producerLimit, ((long) (batchSize * 2)) + pIndex);
                if (pIndex == producerLimit || producerLimit < batchIndex) {
                    switch (offerSlowPath(mask, pIndex, producerLimit)) {
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
                        LinkedAtomicArrayQueueUtil.soElement(buffer, LinkedAtomicArrayQueueUtil.modifiedCalcElementOffset(((long) (i2 * 2)) + pIndex, mask), s.get());
                    }
                    return i;
                }
            }
        }
    }

    public void fill(Supplier<E> s, WaitStrategy w, ExitCondition exit) {
        while (exit.keepRunning()) {
            while (fill(s, PortableJvmInfo.RECOMENDED_OFFER_BATCH) != 0) {
                if (!exit.keepRunning()) {
                    break;
                }
            }
            int idleCounter = 0;
            while (exit.keepRunning() && fill(s, PortableJvmInfo.RECOMENDED_OFFER_BATCH) == 0) {
                idleCounter = w.idle(idleCounter);
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

    private void resize(long oldMask, AtomicReferenceArray<E> oldBuffer, long pIndex, E e) {
        int newBufferLength = getNextBufferSize(oldBuffer);
        AtomicReferenceArray<E> newBuffer = LinkedAtomicArrayQueueUtil.allocate(newBufferLength);
        this.producerBuffer = newBuffer;
        int newMask = (newBufferLength - 2) << 1;
        this.producerMask = (long) newMask;
        int offsetInOld = LinkedAtomicArrayQueueUtil.modifiedCalcElementOffset(pIndex, oldMask);
        LinkedAtomicArrayQueueUtil.soElement(newBuffer, LinkedAtomicArrayQueueUtil.modifiedCalcElementOffset(pIndex, (long) newMask), e);
        LinkedAtomicArrayQueueUtil.soElement(oldBuffer, nextArrayOffset(oldMask), newBuffer);
        long availableInQueue = availableInQueue(pIndex, lvConsumerIndex());
        RangeUtil.checkPositive(availableInQueue, "availableInQueue");
        soProducerLimit(Math.min((long) newMask, availableInQueue) + pIndex);
        soProducerIndex(2 + pIndex);
        LinkedAtomicArrayQueueUtil.soElement(oldBuffer, offsetInOld, JUMP);
    }
}
