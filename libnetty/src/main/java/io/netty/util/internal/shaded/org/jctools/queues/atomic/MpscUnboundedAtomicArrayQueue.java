package io.netty.util.internal.shaded.org.jctools.queues.atomic;

import io.netty.util.internal.shaded.org.jctools.queues.MessagePassingQueue.Consumer;
import io.netty.util.internal.shaded.org.jctools.queues.MessagePassingQueue.Supplier;
import io.netty.util.internal.shaded.org.jctools.util.PortableJvmInfo;
import java.util.concurrent.atomic.AtomicReferenceArray;

public class MpscUnboundedAtomicArrayQueue<E> extends BaseMpscLinkedAtomicArrayQueue<E> {
    long p0;
    long p1;
    long p10;
    long p11;
    long p12;
    long p13;
    long p14;
    long p15;
    long p16;
    long p17;
    long p2;
    long p3;
    long p4;
    long p5;
    long p6;
    long p7;

    public MpscUnboundedAtomicArrayQueue(int chunkSize) {
        super(chunkSize);
    }

    protected long availableInQueue(long pIndex, long cIndex) {
        return 2147483647L;
    }

    public int capacity() {
        return -1;
    }

    public int drain(Consumer<E> c) {
        return drain(c, 4096);
    }

    public int fill(Supplier<E> s) {
        long result = 0;
        do {
            int filled = fill(s, PortableJvmInfo.RECOMENDED_OFFER_BATCH);
            if (filled == 0) {
                return (int) result;
            }
            result += (long) filled;
        } while (result <= 4096);
        return (int) result;
    }

    protected int getNextBufferSize(AtomicReferenceArray<E> buffer) {
        return LinkedAtomicArrayQueueUtil.length(buffer);
    }

    protected long getCurrentBufferCapacity(long mask) {
        return mask;
    }
}
