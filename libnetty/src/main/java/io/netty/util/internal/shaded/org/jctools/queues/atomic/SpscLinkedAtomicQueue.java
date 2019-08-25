package io.netty.util.internal.shaded.org.jctools.queues.atomic;

import io.netty.util.internal.shaded.org.jctools.queues.MessagePassingQueue.Consumer;
import io.netty.util.internal.shaded.org.jctools.queues.MessagePassingQueue.ExitCondition;
import io.netty.util.internal.shaded.org.jctools.queues.MessagePassingQueue.Supplier;
import io.netty.util.internal.shaded.org.jctools.queues.MessagePassingQueue.WaitStrategy;

public class SpscLinkedAtomicQueue<E> extends BaseLinkedAtomicQueue<E> {
    public /* bridge */ /* synthetic */ int capacity() {
        return super.capacity();
    }

    public /* bridge */ /* synthetic */ int drain(Consumer consumer) {
        return super.drain(consumer);
    }

    public /* bridge */ /* synthetic */ int drain(Consumer consumer, int i) {
        return super.drain(consumer, i);
    }

    public /* bridge */ /* synthetic */ void drain(Consumer consumer, WaitStrategy waitStrategy, ExitCondition exitCondition) {
        super.drain(consumer, waitStrategy, exitCondition);
    }

    public /* bridge */ /* synthetic */ boolean relaxedOffer(Object obj) {
        return super.relaxedOffer((E) obj);
    }

    public /* bridge */ /* synthetic */ E relaxedPeek() {
        return super.relaxedPeek();
    }

    public /* bridge */ /* synthetic */ E relaxedPoll() {
        return super.relaxedPoll();
    }

    public /* bridge */ /* synthetic */ String toString() {
        return super.toString();
    }

    public SpscLinkedAtomicQueue() {
        LinkedQueueAtomicNode<E> node = newNode();
        spProducerNode(node);
        spConsumerNode(node);
        node.soNext(null);
    }

    public boolean offer(E e) {
        if (e == null) {
            throw new NullPointerException();
        }
        LinkedQueueAtomicNode<E> nextNode = newNode(e);
        lpProducerNode().soNext(nextNode);
        spProducerNode(nextNode);
        return true;
    }

    public E poll() {
        return relaxedPoll();
    }

    public E peek() {
        return relaxedPeek();
    }

    public int fill(Supplier<E> s) {
        long result = 0;
        do {
            fill(s, 4096);
            result += 4096;
        } while (result <= 2147479551);
        return (int) result;
    }

    public int fill(Supplier<E> s, int limit) {
        if (limit == 0) {
            return 0;
        }
        LinkedQueueAtomicNode<E> tail = newNode(s.get());
        LinkedQueueAtomicNode<E> head = tail;
        for (int i = 1; i < limit; i++) {
            LinkedQueueAtomicNode<E> temp = newNode(s.get());
            tail.soNext(temp);
            tail = temp;
        }
        lpProducerNode().soNext(head);
        spProducerNode(tail);
        return limit;
    }

    public void fill(Supplier<E> s, WaitStrategy wait, ExitCondition exit) {
        LinkedQueueAtomicNode<E> chaserNode = this.producerNode;
        while (exit.keepRunning()) {
            for (int i = 0; i < 4096; i++) {
                LinkedQueueAtomicNode<E> nextNode = newNode(s.get());
                chaserNode.soNext(nextNode);
                chaserNode = nextNode;
                this.producerNode = chaserNode;
            }
        }
    }
}
