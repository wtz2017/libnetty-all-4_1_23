package io.netty.util.internal.shaded.org.jctools.queues;

import io.netty.util.internal.shaded.org.jctools.queues.MessagePassingQueue.Consumer;
import io.netty.util.internal.shaded.org.jctools.queues.MessagePassingQueue.ExitCondition;
import io.netty.util.internal.shaded.org.jctools.queues.MessagePassingQueue.Supplier;
import io.netty.util.internal.shaded.org.jctools.queues.MessagePassingQueue.WaitStrategy;
import io.netty.util.internal.shaded.org.jctools.util.PortableJvmInfo;
import io.netty.util.internal.shaded.org.jctools.util.UnsafeRefArrayAccess;

public class MpscArrayQueue<E> extends MpscArrayQueueL3Pad<E> {
    public MpscArrayQueue(int capacity) {
        super(capacity);
    }

    public boolean offerIfBelowThreshold(E e, int threshold) {
        if (e == null) {
            throw new NullPointerException();
        }
        long pIndex;
        long mask = this.mask;
        long capacity = mask + 1;
        long producerLimit = lvProducerLimit();
        do {
            pIndex = lvProducerIndex();
            if (capacity - (producerLimit - pIndex) >= ((long) threshold)) {
                long cIndex = lvConsumerIndex();
                if (pIndex - cIndex >= ((long) threshold)) {
                    return false;
                }
                producerLimit = cIndex + capacity;
                soProducerLimit(producerLimit);
            }
        } while (!casProducerIndex(pIndex, 1 + pIndex));
        UnsafeRefArrayAccess.soElement(this.buffer, ConcurrentCircularArrayQueue.calcElementOffset(pIndex, mask), e);
        return true;
    }

    public boolean offer(E e) {
        if (e == null) {
            throw new NullPointerException();
        }
        long pIndex;
        long mask = this.mask;
        long producerLimit = lvProducerLimit();
        do {
            pIndex = lvProducerIndex();
            if (pIndex >= producerLimit) {
                producerLimit = (lvConsumerIndex() + mask) + 1;
                if (pIndex >= producerLimit) {
                    return false;
                }
                soProducerLimit(producerLimit);
            }
        } while (!casProducerIndex(pIndex, pIndex + 1));
        UnsafeRefArrayAccess.soElement(this.buffer, ConcurrentCircularArrayQueue.calcElementOffset(pIndex, mask), e);
        return true;
    }

    public final int failFastOffer(E e) {
        if (e == null) {
            throw new NullPointerException();
        }
        long mask = this.mask;
        long capacity = mask + 1;
        long pIndex = lvProducerIndex();
        if (pIndex >= lvProducerLimit()) {
            long producerLimit = lvConsumerIndex() + capacity;
            if (pIndex >= producerLimit) {
                return 1;
            }
            soProducerLimit(producerLimit);
        }
        if (!casProducerIndex(pIndex, 1 + pIndex)) {
            return -1;
        }
        UnsafeRefArrayAccess.soElement(this.buffer, ConcurrentCircularArrayQueue.calcElementOffset(pIndex, mask), e);
        return 0;
    }

    public E poll() {
        long cIndex = lpConsumerIndex();
        long offset = calcElementOffset(cIndex);
        E[] buffer = this.buffer;
        E e = UnsafeRefArrayAccess.lvElement(buffer, offset);
        if (e != null) {
            UnsafeRefArrayAccess.spElement(buffer, offset, null);
            soConsumerIndex(1 + cIndex);
        } else if (cIndex == lvProducerIndex()) {
            return null;
        } else {
            do {
                e = UnsafeRefArrayAccess.lvElement(buffer, offset);
            } while (e == null);
        }
        UnsafeRefArrayAccess.spElement(buffer, offset, null);
        soConsumerIndex(1 + cIndex);
        return e;
    }

    public E peek() {
        E[] buffer = this.buffer;
        long cIndex = lpConsumerIndex();
        long offset = calcElementOffset(cIndex);
        E e = UnsafeRefArrayAccess.lvElement(buffer, offset);
        if (e == null) {
            if (cIndex == lvProducerIndex()) {
                return null;
            }
            do {
                e = UnsafeRefArrayAccess.lvElement(buffer, offset);
            } while (e == null);
        }
        return e;
    }

    public boolean relaxedOffer(E e) {
        return offer(e);
    }

    public E relaxedPoll() {
        E[] buffer = this.buffer;
        long cIndex = lpConsumerIndex();
        long offset = calcElementOffset(cIndex);
        E e = UnsafeRefArrayAccess.lvElement(buffer, offset);
        if (e == null) {
            return null;
        }
        UnsafeRefArrayAccess.spElement(buffer, offset, null);
        soConsumerIndex(1 + cIndex);
        return e;
    }

    public E relaxedPeek() {
        return UnsafeRefArrayAccess.lvElement(this.buffer, ConcurrentCircularArrayQueue.calcElementOffset(lpConsumerIndex(), this.mask));
    }

    public int drain(Consumer<E> c) {
        return drain(c, capacity());
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

    public int drain(Consumer<E> c, int limit) {
        E[] buffer = this.buffer;
        long mask = this.mask;
        long cIndex = lpConsumerIndex();
        for (int i = 0; i < limit; i++) {
            long index = cIndex + ((long) i);
            long offset = ConcurrentCircularArrayQueue.calcElementOffset(index, mask);
            E e = UnsafeRefArrayAccess.lvElement(buffer, offset);
            if (e == null) {
                return i;
            }
            UnsafeRefArrayAccess.spElement(buffer, offset, null);
            soConsumerIndex(1 + index);
            c.accept(e);
        }
        return limit;
    }

    public int fill(Supplier<E> s, int limit) {
        int actualLimit;
        long mask = this.mask;
        long capacity = mask + 1;
        long producerLimit = lvProducerLimit();
        long pIndex;
        do {
            pIndex = lvProducerIndex();
            long available = producerLimit - pIndex;
            if (available <= 0) {
                producerLimit = lvConsumerIndex() + capacity;
                available = producerLimit - pIndex;
                if (available <= 0) {
                    return 0;
                }
                soProducerLimit(producerLimit);
            }
            actualLimit = Math.min((int) available, limit);
        } while (!casProducerIndex(pIndex, ((long) actualLimit) + pIndex));
        E[] buffer = this.buffer;
        for (int i = 0; i < actualLimit; i++) {
            UnsafeRefArrayAccess.soElement(buffer, ConcurrentCircularArrayQueue.calcElementOffset(((long) i) + pIndex, mask), s.get());
        }
        return actualLimit;
    }

    public void drain(Consumer<E> c, WaitStrategy w, ExitCondition exit) {
        E[] buffer = this.buffer;
        long mask = this.mask;
        long cIndex = lpConsumerIndex();
        int counter = 0;
        while (exit.keepRunning()) {
            for (int i = 0; i < 4096; i++) {
                long offset = ConcurrentCircularArrayQueue.calcElementOffset(cIndex, mask);
                E e = UnsafeRefArrayAccess.lvElement(buffer, offset);
                if (e == null) {
                    counter = w.idle(counter);
                } else {
                    cIndex++;
                    counter = 0;
                    UnsafeRefArrayAccess.spElement(buffer, offset, null);
                    soConsumerIndex(cIndex);
                    c.accept(e);
                }
            }
        }
    }

    public void fill(Supplier<E> s, WaitStrategy w, ExitCondition exit) {
        int idleCounter = 0;
        while (exit.keepRunning()) {
            if (fill(s, PortableJvmInfo.RECOMENDED_OFFER_BATCH) == 0) {
                idleCounter = w.idle(idleCounter);
            } else {
                idleCounter = 0;
            }
        }
    }
}
