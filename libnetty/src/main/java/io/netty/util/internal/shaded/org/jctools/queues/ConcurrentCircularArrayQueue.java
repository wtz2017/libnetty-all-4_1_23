package io.netty.util.internal.shaded.org.jctools.queues;

import io.netty.util.internal.shaded.org.jctools.util.Pow2;
import java.util.Iterator;

public abstract class ConcurrentCircularArrayQueue<E> extends ConcurrentCircularArrayQueueL0Pad<E> {
    protected final E[] buffer;
    protected final long mask;

    public ConcurrentCircularArrayQueue(int capacity) {
        int actualCapacity = Pow2.roundToPowerOfTwo(capacity);
        this.mask = (long) (actualCapacity - 1);
        this.buffer = CircularArrayOffsetCalculator.allocate(actualCapacity);
    }

    protected static long calcElementOffset(long index, long mask) {
        return CircularArrayOffsetCalculator.calcElementOffset(index, mask);
    }

    protected final long calcElementOffset(long index) {
        return calcElementOffset(index, this.mask);
    }

    public Iterator<E> iterator() {
        throw new UnsupportedOperationException();
    }

    public final int size() {
        return IndexedQueueSizeUtil.size(this);
    }

    public final boolean isEmpty() {
        return IndexedQueueSizeUtil.isEmpty(this);
    }

    public String toString() {
        return getClass().getName();
    }

    public void clear() {
        do {
        } while (poll() != null);
    }

    public int capacity() {
        return (int) (this.mask + 1);
    }

    public final long currentProducerIndex() {
        return lvProducerIndex();
    }

    public final long currentConsumerIndex() {
        return lvConsumerIndex();
    }
}
