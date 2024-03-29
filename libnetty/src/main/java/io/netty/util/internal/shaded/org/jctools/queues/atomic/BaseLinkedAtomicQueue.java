package io.netty.util.internal.shaded.org.jctools.queues.atomic;

import io.netty.util.internal.shaded.org.jctools.queues.MessagePassingQueue.Consumer;
import io.netty.util.internal.shaded.org.jctools.queues.MessagePassingQueue.ExitCondition;
import io.netty.util.internal.shaded.org.jctools.queues.MessagePassingQueue.WaitStrategy;
import java.util.Iterator;

abstract class BaseLinkedAtomicQueue<E> extends BaseLinkedAtomicQueuePad2<E> {
    BaseLinkedAtomicQueue() {
    }

    public final Iterator<E> iterator() {
        throw new UnsupportedOperationException();
    }

    public String toString() {
        return getClass().getName();
    }

    protected final LinkedQueueAtomicNode<E> newNode() {
        return new LinkedQueueAtomicNode();
    }

    protected final LinkedQueueAtomicNode<E> newNode(E e) {
        return new LinkedQueueAtomicNode(e);
    }

    public final int size() {
        LinkedQueueAtomicNode<E> chaserNode = lvConsumerNode();
        LinkedQueueAtomicNode<E> producerNode = lvProducerNode();
        int size = 0;
        while (chaserNode != producerNode && chaserNode != null && size < Integer.MAX_VALUE) {
            LinkedQueueAtomicNode<E> next = chaserNode.lvNext();
            if (next == chaserNode) {
                break;
            }
            chaserNode = next;
            size++;
        }
        return size;
    }

    public final boolean isEmpty() {
        return lvConsumerNode() == lvProducerNode();
    }

    protected E getSingleConsumerNodeValue(LinkedQueueAtomicNode<E> currConsumerNode, LinkedQueueAtomicNode<E> nextNode) {
        E nextValue = nextNode.getAndNullValue();
        currConsumerNode.soNext(currConsumerNode);
        spConsumerNode(nextNode);
        return nextValue;
    }

    public E relaxedPoll() {
        LinkedQueueAtomicNode<E> currConsumerNode = lpConsumerNode();
        LinkedQueueAtomicNode<E> nextNode = currConsumerNode.lvNext();
        if (nextNode != null) {
            return getSingleConsumerNodeValue(currConsumerNode, nextNode);
        }
        return null;
    }

    public E relaxedPeek() {
        LinkedQueueAtomicNode<E> nextNode = lpConsumerNode().lvNext();
        if (nextNode != null) {
            return nextNode.lpValue();
        }
        return null;
    }

    public boolean relaxedOffer(E e) {
        return offer(e);
    }

    public int drain(Consumer<E> c) {
        long result = 0;
        do {
            int drained = drain(c, 4096);
            result += (long) drained;
            if (drained != 4096) {
                break;
            }
        } while (result <= 2147479551);
        return (int) result;
    }

    public int drain(Consumer<E> c, int limit) {
        LinkedQueueAtomicNode<E> chaserNode = this.consumerNode;
        for (int i = 0; i < limit; i++) {
            LinkedQueueAtomicNode<E> nextNode = chaserNode.lvNext();
            if (nextNode == null) {
                return i;
            }
            E nextValue = getSingleConsumerNodeValue(chaserNode, nextNode);
            chaserNode = nextNode;
            c.accept(nextValue);
        }
        return limit;
    }

    public void drain(Consumer<E> c, WaitStrategy wait, ExitCondition exit) {
        LinkedQueueAtomicNode<E> chaserNode = this.consumerNode;
        int idleCounter = 0;
        while (exit.keepRunning()) {
            for (int i = 0; i < 4096; i++) {
                LinkedQueueAtomicNode<E> nextNode = chaserNode.lvNext();
                if (nextNode == null) {
                    idleCounter = wait.idle(idleCounter);
                } else {
                    idleCounter = 0;
                    E nextValue = getSingleConsumerNodeValue(chaserNode, nextNode);
                    chaserNode = nextNode;
                    c.accept(nextValue);
                }
            }
        }
    }

    public int capacity() {
        return -1;
    }
}
