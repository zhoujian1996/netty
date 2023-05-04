/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.util.concurrent;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static io.netty.util.internal.ObjectUtil.checkPositive;

/**
 * Abstract base class for {@link EventExecutorGroup} implementations that handles their tasks with multiple threads at
 * the same time.
 */
public abstract class MultithreadEventExecutorGroup extends AbstractEventExecutorGroup {

    private final EventExecutor[] children;
    private final Set<EventExecutor> readonlyChildren;
    private final AtomicInteger terminatedChildren = new AtomicInteger();
    private final Promise<?> terminationFuture = new DefaultPromise(GlobalEventExecutor.INSTANCE);
    private final EventExecutorChooserFactory.EventExecutorChooser chooser; // 向eventLoopGroup提交任务，包括注册Channel，怎样选择合适的eventLoop

    /**
     * Create a new instance.
     *
     * @param nThreads          the number of threads that will be used by this instance.
     * @param threadFactory     the ThreadFactory to use, or {@code null} if the default should be used.
     * @param args              arguments which will passed to each {@link #newChild(Executor, Object...)} call
     */
    protected MultithreadEventExecutorGroup(int nThreads, ThreadFactory threadFactory, Object... args) {
        this(nThreads, threadFactory == null ? null : new ThreadPerTaskExecutor(threadFactory), args);
    }

    /**
     * Create a new instance.
     *
     * @param nThreads          the number of threads that will be used by this instance.
     * @param executor          the Executor to use, or {@code null} if the default should be used.
     * @param args              arguments which will passed to each {@link #newChild(Executor, Object...)} call
     */
    protected MultithreadEventExecutorGroup(int nThreads, Executor executor, Object... args) {
        /**
         * args有：
         * KqueSelectorProvider
         * SelectStrategyFactory
         * RejectedExecutionHandleer
         *
         */
        this(nThreads, executor, DefaultEventExecutorChooserFactory.INSTANCE, args);
    }

    /**
     * Create a new instance.
     * 创建一个多线程的事件执行器组
     * @param nThreads          the number of threads that will be used by this instance.
     * @param executor          the Executor to use, or {@code null} if the default should be used.
     * @param chooserFactory    the {@link EventExecutorChooserFactory} to use.
     * @param args              arguments which will passed to each {@link #newChild(Executor, Object...)} call
     *                                  /**
     *          * args有：
     *          * KqueSelectorProvider
     *          * SelectStrategyFactory
     *          * RejectedExecutionHandleer
     *          *
     *          */
    protected MultithreadEventExecutorGroup(int nThreads, Executor executor,
                                            EventExecutorChooserFactory chooserFactory, Object... args) {
        // 确保线程数大于0
        checkPositive(nThreads, "nThreads");

        // 1、创建ThreadPerTaskExecutor
          /*
            如果没提供Executor，则创建默认的ThreadPerTaskExecutor。
            ThreadPerTaskExecutor依赖于一个ThreadFactory，靠它创建线程来执行任务。
            默认的ThreadFactory会使用FastThreadLocalThread来提升FastThreadLocal的性能。
             */
        if (executor == null) {
            // newDefaultThreadFactory() 创建县城的工厂
            executor = new ThreadPerTaskExecutor(newDefaultThreadFactory());
        }

        // 2、创建NioeventLoop    // 创建子EventExecutor
        /**
         * 创建NioEventLoop： NioEventLoop对应线程池里线程的概念， 这
         * 里其实就是用一个for循环创建的。
         * Netty使用for循环来创建nThreads个NioEventLoop， 通过前面的分
         * 析， 我们可能已经猜到， 一个NioEventLoop对应一个线程实体， 这个线
         * 程实体是FastThreadLocalThread。
         */
        children = new EventExecutor[nThreads];
        for (int i = 0; i < nThreads; i ++) {
            boolean success = false;
            try {
                children[i] = newChild(executor, args);
                success = true;
            } catch (Exception e) {
                // TODO: Think about if this is a good exception type
                throw new IllegalStateException("failed to create a child event loop", e);
            } finally {
                //                 // EventExecutor创建失败，停机释放资源
                if (!success) {
                    for (int j = 0; j < i; j ++) {
                        children[j].shutdownGracefully();
                    }
                    for (int j = 0; j < i; j ++) {
                        EventExecutor e = children[j];
                        try {
                            while (!e.isTerminated()) {
                                e.awaitTermination(Integer.MAX_VALUE, TimeUnit.SECONDS);
                            }
                        } catch (InterruptedException interrupted) {
                            // Let the caller handle the interruption.
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            }
        }

        // 3、创建线程选择器
        /**
         * 创建线程选择器： 线程选择器的作用是确定每次如何从线程池中
         * 选择一个线程， 也就是每次如何从NioEventLoopGroup中选择一个
         * NioEventLoop
         *  创建选择器:简单轮询
         *         PowerOfTwoEventExecutorChooser:2的幂次方，位运算
         *         GenericEventExecutorChooser:否则，取余
         *     有事件/任务要执行时，取出一个EventExecutor
         *     EventLoopGroup本身不干活，向它提交任务，它只会交给它的孩子EventLoop执行，所以它依赖一个EventExecutorChooser，
         *     当有任务来临时，从众多的孩子中挑选出一个，默认的选择策略就是简单轮询。
         * Netty这里做了一个小小的优化，如果孩子数量是2的幂次方数会使用位运算，否则取模。
         * 有了选择器后，你向EventLoopGroup提交的任务，包括注册Channel，它都会轮询出一个EventLoop转交任务
         */
        chooser = chooserFactory.newChooser(children);

        //     // 所有children停止时收到一个通知，优雅停机时用到
        final FutureListener<Object> terminationListener = new FutureListener<Object>() {
            @Override
            public void operationComplete(Future<Object> future) throws Exception {
                if (terminatedChildren.incrementAndGet() == children.length) {
                    terminationFuture.setSuccess(null);
                }
            }
        };
        for (EventExecutor e: children) {
            e.terminationFuture().addListener(terminationListener);
        }
    //     // 返回一个只读的children，iterator()迭代时使用
        Set<EventExecutor> childrenSet = new LinkedHashSet<EventExecutor>(children.length);
        Collections.addAll(childrenSet, children);
        readonlyChildren = Collections.unmodifiableSet(childrenSet);
    }

    /**
     * 创建默认的threadFactory
     * 这里的getClass()调用的对象是NioEventLoopGroup， 因为我们是通 过NioEventLoopGroup的构造方法层层调用到这里的。
     * @return
     */
    protected ThreadFactory newDefaultThreadFactory() {
        return new DefaultThreadFactory(getClass());
    }

    @Override
    public EventExecutor next() {
        return chooser.next();
    }

    @Override
    public Iterator<EventExecutor> iterator() {
        return readonlyChildren.iterator();
    }

    /**
     * Return the number of {@link EventExecutor} this implementation uses. This number is the maps
     * 1:1 to the threads it use.
     */
    public final int executorCount() {
        return children.length;
    }

    /**
     * Create a new EventExecutor which will later then accessible via the {@link #next()}  method. This method will be
     * called for each thread that will serve this {@link MultithreadEventExecutorGroup}.
     *newChild传递一个executor参数， 这个参数就是前面分析的
     * ThreadPerTaskExecutor， 而args参数是我们通过层层调用传递过来的一
     * 系列参数。
     */
    protected abstract EventExecutor newChild(Executor executor, Object... args) throws Exception;

    @Override
    public Future<?> shutdownGracefully(long quietPeriod, long timeout, TimeUnit unit) {
        for (EventExecutor l: children) {
            l.shutdownGracefully(quietPeriod, timeout, unit);
        }
        return terminationFuture();
    }

    @Override
    public Future<?> terminationFuture() {
        return terminationFuture;
    }

    @Override
    @Deprecated
    public void shutdown() {
        for (EventExecutor l: children) {
            l.shutdown();
        }
    }

    @Override
    public boolean isShuttingDown() {
        for (EventExecutor l: children) {
            if (!l.isShuttingDown()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean isShutdown() {
        for (EventExecutor l: children) {
            if (!l.isShutdown()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean isTerminated() {
        for (EventExecutor l: children) {
            if (!l.isTerminated()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit)
            throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        loop: for (EventExecutor l: children) {
            for (;;) {
                long timeLeft = deadline - System.nanoTime();
                if (timeLeft <= 0) {
                    break loop;
                }
                if (l.awaitTermination(timeLeft, TimeUnit.NANOSECONDS)) {
                    break;
                }
            }
        }
        return isTerminated();
    }
}
