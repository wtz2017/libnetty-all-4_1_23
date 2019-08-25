package io.netty.util.internal.shaded.org.jctools.queues.atomic;

import io.netty.util.internal.shaded.org.jctools.util.Pow2;
import io.netty.util.internal.shaded.org.jctools.util.RangeUtil;
import java.util.concurrent.atomic.AtomicReferenceArray;

public class MpscGrowableAtomicArrayQueue<E> extends MpscChunkedAtomicArrayQueue<E> {
    public MpscGrowableAtomicArrayQueue(int maxCapacity) {
        super(Math.max(2, Pow2.roundToPowerOfTwo(maxCapacity / 8)), maxCapacity);
    }

    public MpscGrowableAtomicArrayQueue(int initialCapacity, int maxCapacity) {
        super(initialCapacity, maxCapacity);
    }

    protected int getNextBufferSize(AtomicReferenceArray<E> buffer) {
        RangeUtil.checkLessThanOrEqual(LinkedAtomicArrayQueueUtil.length(buffer), this.maxQueueCapacity / 2, "buffer.length");
        return ((LinkedAtomicArrayQueueUtil.length(buffer) - 1) * 2) + 1;
    }

    protected long getCurrentBufferCapacity(long mask) {
        return 2 + mask == this.maxQueueCapacity ? this.maxQueueCapacity : mask;
    }
}
