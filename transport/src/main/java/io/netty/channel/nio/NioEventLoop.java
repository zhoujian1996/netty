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
package io.netty.channel.nio;

import io.netty.channel.Channel;
import io.netty.channel.ChannelException;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopException;
import io.netty.channel.EventLoopTaskQueueFactory;
import io.netty.channel.SelectStrategy;
import io.netty.channel.SingleThreadEventLoop;
import io.netty.util.IntSupplier;
import io.netty.util.concurrent.RejectedExecutionHandler;
import io.netty.util.internal.ObjectUtil;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.ReflectionUtil;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.Selector;
import java.nio.channels.SelectionKey;

import java.nio.channels.spi.SelectorProvider;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link SingleThreadEventLoop} implementation which register the {@link Channel}'s to a
 * {@link Selector} and so does the multi-plexing of these in the event loop.
 * <p>
 * NioEventLoop的主要职责是负责处理注册到其上的Channel的IO事件，
 * 除此之外它还可以执行用户提交的系统任务和定时任务，
 * 例如：你可以每隔一段时间检查一下连接是否断开，
 * 如果断开，客户端可以重连，服务端需要及时释放资源。
 * 一个Channel只能被注册到一个EventLoop上，一个EventLoop可以注册多个Channel。
 * <p>
 * 一旦Channel注册到EventLoop，该EventLoop就要负责处理它整个生命周期的所有事件。
 * 事件以回调的方式被触发，
 * 所有的回调任务会被封装成一个Runnable放入taskQueue，由EventLoop线程串行化处理。
 * 虽然看似「串行化处理」效率低下，
 * 但是这避免了线程切换的开销和数据同步的问题，而且你可以开启多个EventLoop，
 * 并行处理，充分利用CPU资源。
 */
public final class NioEventLoop extends SingleThreadEventLoop {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(NioEventLoop.class);
    // // 是否禁用SelectionKey优化？默认为false
    private static final int CLEANUP_INTERVAL = 256; // XXX Hard-coded value, but won't need customization.
    //     // 如果禁用了SelectionKey优化，则unwrappedSelector和selector都指向原生Selector
    private static final boolean DISABLE_KEY_SET_OPTIMIZATION = SystemPropertyUtil.getBoolean("io.netty.noKeySetOptimization", false);

    private static final int MIN_PREMATURE_SELECTOR_RETURNS = 3;
    // // Selector重建的阈值，默认512，目的是解决JDK Selector空轮询Bug
    private static final int SELECTOR_AUTO_REBUILD_THRESHOLD;

    // // 当前有几个准备就绪的Channel？selectStrategy会用到，大于0代表有Channel事件需要处理
    private final IntSupplier selectNowSupplier = new IntSupplier() {
        @Override
        public int get() throws Exception {
            return selectNow();
        }
    };

    // Workaround for JDK NIO bug.
    //
    // See:
    // - https://bugs.openjdk.java.net/browse/JDK-6427854 for first few dev (unreleased) builds of JDK 7
    // - https://bugs.openjdk.java.net/browse/JDK-6527572 for JDK prior to 5.0u15-rev and 6u10
    // - https://github.com/netty/netty/issues/203
    static {
        if (PlatformDependent.javaVersion() < 7) {
            final String key = "sun.nio.ch.bugLevel";
            final String bugLevel = SystemPropertyUtil.get(key);
            if (bugLevel == null) {
                try {
                    AccessController.doPrivileged(new PrivilegedAction<Void>() {
                        @Override
                        public Void run() {
                            System.setProperty(key, "");
                            return null;
                        }
                    });
                } catch (final SecurityException e) {
                    logger.debug("Unable to get/set System Property: " + key, e);
                }
            }
        }

        int selectorAutoRebuildThreshold = SystemPropertyUtil.getInt("io.netty.selectorAutoRebuildThreshold", 512);
        if (selectorAutoRebuildThreshold < MIN_PREMATURE_SELECTOR_RETURNS) {
            selectorAutoRebuildThreshold = 0;
        }

        SELECTOR_AUTO_REBUILD_THRESHOLD = selectorAutoRebuildThreshold;

        if (logger.isDebugEnabled()) {
            logger.debug("-Dio.netty.noKeySetOptimization: {}", DISABLE_KEY_SET_OPTIMIZATION);
            logger.debug("-Dio.netty.selectorAutoRebuildThreshold: {}", SELECTOR_AUTO_REBUILD_THRESHOLD);
        }
    }

    /**
     * The NIO {@link Selector}.
     * 每个NioEventLoop被创建时，都会同时创建一个Selector多路复用器，
     * 这是JDK提供的，不熟悉的同学去看看Java Nio编程。
     * EventLoopGroup会将Channel注册到NioEventLoop上，实际上就是注册到Selector上了。
     * 这样NioEventLoop就可以通过Selector来监听准备就绪的Channel，然后根据事件类型去触发相应的回调，所以Selector是NioEventLoop的核心。
     */
    private Selector selector; //     每个EventLoop都会有一个Selector，用来监听注册在其上的Channel事件。对于BossGroup，处理的是Accept 对于WorkerGroup，处理的是read、write...
    private Selector unwrappedSelector;
    private SelectedSelectionKeySet selectedKeys;

    private final SelectorProvider provider;

    private static final long AWAKE = -1L;
    private static final long NONE = Long.MAX_VALUE;

    // nextWakeupNanos is:
    //    AWAKE            when EL is awake
    //    NONE             when EL is waiting with no wakeup scheduled
    //    other value T    when EL is waiting with wakeup scheduled at time T
    private final AtomicLong nextWakeupNanos = new AtomicLong(AWAKE);

    private final SelectStrategy selectStrategy;

    private volatile int ioRatio = 50;
    private int cancelledKeys;
    private boolean needsToSelectAgain;

    /**
     * 创建一个NioEventLoop实例，用来执行注册 在其上的Channel事件
     *
     * @param parent                   所属Group EventLoop隶属的EventLoopGroup，由Group来管理和调度。
     * @param executor                 EventLoop需要executor开启新线程跑自身的run()方法。
     * @param selectorProvider         多路复用器提供者，不同平台会使用不同实现
     * @param strategy                 select策略工厂，指示EventLoop应该要做什么事情。
     * @param rejectedExecutionHandler 拒绝策略
     * @param taskQueueFactory         任务队列工厂，负责创建taskQueue。
     * @param tailTaskQueueFactory     我们同样略去了非关键代码。 首先， 继续调用父类构造方法； 然
     *                                 后， 通过调用openSelector方法来创建一个Selector。 Selector是NIO编程
     *                                 里最核心的概念， 一个Selector可以将多个连接绑定在一起， 负责监听
     *                                 这些连接的读写事件， 即多路复用
     *                                 <p>
     *                                 这个方法只是将eventLoop创建出来，但此时线程还没有启动，Netty在这里做了蓝家在
     *                                 用户不主动提交任务的前提下，
     *                                 对于BossGroup的EventLoop来说，线程是在调用bind()方法将ServerSocketChannel注册到EventLoop时被启动的。
     *                                 对于WorkerGroup的EventLoop来说，线程是在BossGroup接收到客户端连接时，将SocketChannel注册到WorkerGroup时被启动的。
     */
    NioEventLoop(NioEventLoopGroup parent, Executor executor,
                 SelectorProvider selectorProvider,
                 SelectStrategy strategy,
                 RejectedExecutionHandler rejectedExecutionHandler,
                 EventLoopTaskQueueFactory taskQueueFactory,
                 EventLoopTaskQueueFactory tailTaskQueueFactory) {

        /**
         * NioEventLoop首先创建了两个TaskQueue来存放待执行的任务，
         * run()方法会不断消费任务。虽然可以多线程并发的往taskQueue中提交任务，
         * 但是由于EventLoop是单线程的，
         * 所有taskQueue的生产消费模型是：**多生产者单消费者。
         * **针对这种消费场景，Netty实现了高性能的无锁队列「MpscQueue」
         */
        super(parent, executor, false, newTaskQueue(taskQueueFactory), newTaskQueue(tailTaskQueueFactory), rejectedExecutionHandler);

        this.provider = ObjectUtil.checkNotNull(selectorProvider, "selectorProvider");
        this.selectStrategy = ObjectUtil.checkNotNull(strategy, "selectStrategy");

    /*
        每个EventLoop都会有一个Selector，用来监听注册在其上的Channel事件。
        对于BossGroup，处理的是Accept。
        对于WorkerGroup，处理的是read、write...
        SelectorTuple:Selector元组，Netty提供了一个Selector包装，用来优化select()性能
     */
        /**
         * 每个NioEventLoop被创建时，都会同时创建一个Selector多路复用器，这是JDK提供的，不熟悉的同学去看看Java Nio编程
         * EventLoopGroup会将Channel注册到NioEventLoop上，实际上就是注册到Selector上了
         * 这样NioEventLoop就可以通过Selector来监听准备就绪的Channel，
         * 然后根据事件类型去触发相应的回调，所以Selector是NioEventLoop的核心。
         */
        final SelectorTuple selectorTuple = openSelector(); //  通过调用openSelector方法来创建一个Selector。 通过调用openSelector方法来创建一个Selector。
        this.selector = selectorTuple.selector; // 优化后的selector
        this.unwrappedSelector = selectorTuple.unwrappedSelector; // wei保障的selectot
        // 创建完Selector，NioEventLoop的初始化就完成了，但此时线程并未启动，
        // Netty这里做了懒加载处理，只有当EventLoop第一次被调用execute()执行任务时才会通过executor去创建线程跑run()方法。
    }

    /**
     * // 创建TaskQueue，存放待执行的任务
     * 虽然可以多线程并发的往taskQueue中提交任务，但是由于EventLoop是单线程的，
     * 所有taskQueue的生产消费模型是：**多生产者单消费者。**针对这种消费场景，Netty实现了高性能的无锁队列「MpscQueue」
     *
     * @param queueFactory
     * @return
     */
    private static Queue<Runnable> newTaskQueue(EventLoopTaskQueueFactory queueFactory) {
        if (queueFactory == null) {
            // 默认创建Netty实现MpscQueue：Netty实现的高性能无锁队列，适用于多个生产者，单个消费者。
            return newTaskQueue0(DEFAULT_MAX_PENDING_TASKS);
        }
        return queueFactory.newTaskQueue(DEFAULT_MAX_PENDING_TASKS);
    }

    private static final class SelectorTuple {
        final Selector unwrappedSelector;
        final Selector selector;

        SelectorTuple(Selector unwrappedSelector) {
            this.unwrappedSelector = unwrappedSelector;
            this.selector = unwrappedSelector;
        }

        SelectorTuple(Selector unwrappedSelector, Selector selector) {
            this.unwrappedSelector = unwrappedSelector;
            this.selector = selector;
        }
    }

    /**
     * 通过调用openSelector方法来创建一个Selector。
     * Selector是NIO编程
     * 里最核心的概念， 一个Selector可以将多个连接绑定在一起， 负责监听
     * 这些连接的读写事件， 即多路复用
     * 在openSelector方法中， Netty通过反射对Selector底层的数据结构进
     * 行了优化。
     * openSelector()会做一个优化，
     * 将JDK的SelectorImpl的selectedKeys、publicSelectedKeys属性由HashSet替换成Netty的SelectedSelectionKeySet，内部是一个数组。
     * 当Selector监听到有准备就绪的Channel时，会往HashSet里添加SelectionKey，当SelectionKey比较多时，就容易发生哈希冲突，时间复杂度会增加，
     * 而SelectedSelectionKeySet内部使用数组来保存，避免了哈希冲突，性能会有一定的提升。
     * 打开一个Selector多路复用器
     *
     * @return
     */
    private SelectorTuple openSelector() {
        final Selector unwrappedSelector; //未包装的原生Selector
        try {
            unwrappedSelector = provider.openSelector(); // 基于SelectorProvider打开一个原生的Selector，这是JDK提供的。
        } catch (IOException e) {
            throw new ChannelException("failed to open a new selector", e);
        }

        //     // 如果禁用了SelectionKey优化，则unwrappedSelector和selector都指向原生Selector
        if (DISABLE_KEY_SET_OPTIMIZATION) {
            return new SelectorTuple(unwrappedSelector);
        }
        // 否则，使用SelectedSelectionKeySet来优化SelectionKey
        Object maybeSelectorImplClass = AccessController.doPrivileged(new PrivilegedAction<Object>() {
            @Override
            public Object run() {
                try {
                    return Class.forName("sun.nio.ch.SelectorImpl", false, PlatformDependent.getSystemClassLoader());
                } catch (Throwable cause) {
                    return cause;
                }
            }
        });

        if (!(maybeSelectorImplClass instanceof Class) ||
                // ensure the current selector implementation is what we can instrument.
                !((Class<?>) maybeSelectorImplClass).isAssignableFrom(unwrappedSelector.getClass())) {
            if (maybeSelectorImplClass instanceof Throwable) {
                Throwable t = (Throwable) maybeSelectorImplClass;
                logger.trace("failed to instrument a special java.util.Set into: {}", unwrappedSelector, t);
            }
            return new SelectorTuple(unwrappedSelector);
        }

        final Class<?> selectorImplClass = (Class<?>) maybeSelectorImplClass;
        final SelectedSelectionKeySet selectedKeySet = new SelectedSelectionKeySet();

        Object maybeException = AccessController.doPrivileged(new PrivilegedAction<Object>() {
            @Override
            public Object run() {
                try {
                    // 反射获取SelectorImpl的selectedKeys、publicSelectedKeys属性
                    Field selectedKeysField = selectorImplClass.getDeclaredField("selectedKeys");
                    Field publicSelectedKeysField = selectorImplClass.getDeclaredField("publicSelectedKeys");

                    if (PlatformDependent.javaVersion() >= 9 && PlatformDependent.hasUnsafe()) {
                        // Let us try to use sun.misc.Unsafe to replace the SelectionKeySet.
                        // This allows us to also do this in Java9+ without any extra flags.
                        long selectedKeysFieldOffset = PlatformDependent.objectFieldOffset(selectedKeysField);
                        long publicSelectedKeysFieldOffset = PlatformDependent.objectFieldOffset(publicSelectedKeysField);

                        if (selectedKeysFieldOffset != -1 && publicSelectedKeysFieldOffset != -1) {
                            PlatformDependent.putObject(unwrappedSelector, selectedKeysFieldOffset, selectedKeySet);
                            PlatformDependent.putObject(unwrappedSelector, publicSelectedKeysFieldOffset, selectedKeySet);
                            return null;
                        }
                        // We could not retrieve the offset, lets try reflection as last-resort.
                    }

                    Throwable cause = ReflectionUtil.trySetAccessible(selectedKeysField, true);
                    if (cause != null) {
                        return cause;
                    }
                    cause = ReflectionUtil.trySetAccessible(publicSelectedKeysField, true);
                    if (cause != null) {
                        return cause;
                    }
  /*
                通过反射将SelectorImpl的selectedKeys、publicSelectedKeys替换为selectedKeySet来提升性能。
                 */
                    selectedKeysField.set(unwrappedSelector, selectedKeySet);
                    publicSelectedKeysField.set(unwrappedSelector, selectedKeySet);
                    return null;
                } catch (NoSuchFieldException e) {
                    return e;
                } catch (IllegalAccessException e) {
                    return e;
                }
            }
        });

        if (maybeException instanceof Exception) {
            selectedKeys = null;
            Exception e = (Exception) maybeException;
            logger.trace("failed to instrument a special java.util.Set into: {}", unwrappedSelector, e);
            return new SelectorTuple(unwrappedSelector);
        }
        selectedKeys = selectedKeySet;
        logger.trace("instrumented a special java.util.Set into: {}", unwrappedSelector);
        //     // 创建一个SelectorTuple，包含一个原生的Selector，和优化过的Selector。
        return new SelectorTuple(unwrappedSelector, new SelectedSelectionKeySetSelector(unwrappedSelector, selectedKeySet));
    }

    /**
     * Returns the {@link SelectorProvider} used by this {@link NioEventLoop} to obtain the {@link Selector}.
     */
    public SelectorProvider selectorProvider() {
        return provider;
    }

    @Override
    protected Queue<Runnable> newTaskQueue(int maxPendingTasks) {
        return newTaskQueue0(maxPendingTasks);
    }

    /**
     * 这里创建的是一个高性能的MPSC队列， 也就是多生产者单消费者
     * 队列， 单消费者指某个NioEventLoop对应的线程， 而多生产者就是此
     * NioEventLoop对应的线程之外的线程， 通常情况下就是我们的业务线
     * 程。 比如， 我们在调用writeAndFlush的时候， 可以不用考虑线程安全，
     * 随意调用， 这些线程指的就是多消费者， 在NioEventLoop的执行部分，
     * 我们会详细分析。
     * <p>
     * 根据最大队列数创建Queue。
     * MpscQueue:Netty实现的高性能无锁队列，适用于多个生产者，单个消费者。
     * 多个线程可以并发往EventLoop提交任务，但是EventLoop本身是单线程消费的。
     *
     * @param maxPendingTasks
     * @return
     */
    private static Queue<Runnable> newTaskQueue0(int maxPendingTasks) {
        // This event loop never calls takeTask()
        return maxPendingTasks == Integer.MAX_VALUE ? PlatformDependent.<Runnable>newMpscQueue() : PlatformDependent.<Runnable>newMpscQueue(maxPendingTasks);
    }

    /**
     * Registers an arbitrary {@link SelectableChannel}, not necessarily created by Netty, to the {@link Selector}
     * of this event loop.  Once the specified {@link SelectableChannel} is registered, the specified {@code task} will
     * be executed by this event loop when the {@link SelectableChannel} is ready.
     * 向此事件循环的 Selector 注册一个任意的 SelectableChannel（不一定是由 Netty 创建的）。
     * 一旦指定的 SelectableChannel 被注册，当该通道准备就绪时，此事件循环将执行指定的任务。
     */
    public void register(final SelectableChannel ch, final int interestOps, final NioTask<?> task) {
        ObjectUtil.checkNotNull(ch, "ch");
        if (interestOps == 0) {
            throw new IllegalArgumentException("interestOps must be non-zero.");
        }
        if ((interestOps & ~ch.validOps()) != 0) {
            throw new IllegalArgumentException("invalid interestOps: " + interestOps + "(validOps: " + ch.validOps() + ')');
        }
        ObjectUtil.checkNotNull(task, "task");

        if (isShutdown()) {
            throw new IllegalStateException("event loop shut down");
        }

        if (inEventLoop()) {
            register0(ch, interestOps, task);
        } else {
            try {
                // Offload to the EventLoop as otherwise java.nio.channels.spi.AbstractSelectableChannel.register
                // may block for a long time while trying to obtain an internal lock that may be hold while selecting.
                submit(new Runnable() {
                    @Override
                    public void run() {
                        register0(ch, interestOps, task);
                    }
                }).sync();
            } catch (InterruptedException ignore) {
                // Even if interrupted we did schedule it so just mark the Thread as interrupted.
                Thread.currentThread().interrupt();
            }
        }
    }

    private void register0(SelectableChannel ch, int interestOps, NioTask<?> task) {
        try {
            ch.register(unwrappedSelector, interestOps, task);
        } catch (Exception e) {
            throw new EventLoopException("failed to register a channel", e);
        }
    }

    /**
     * Returns the percentage of the desired amount of time spent for I/O in the event loop.
     */
    public int getIoRatio() {
        return ioRatio;
    }

    /**
     * Sets the percentage of the desired amount of time spent for I/O in the event loop. Value range from 1-100.
     * The default value is {@code 50}, which means the event loop will try to spend the same amount of time for I/O
     * as for non-I/O tasks. The lower the number the more time can be spent on non-I/O tasks. If value set to
     * {@code 100}, this feature will be disabled and event loop will not attempt to balance I/O and non-I/O tasks.
     */
    public void setIoRatio(int ioRatio) {
        if (ioRatio <= 0 || ioRatio > 100) {
            throw new IllegalArgumentException("ioRatio: " + ioRatio + " (expected: 0 < ioRatio <= 100)");
        }
        this.ioRatio = ioRatio;
    }

    /**
     * Replaces the current {@link Selector} of this event loop with newly created {@link Selector}s to work
     * around the infamous epoll 100% CPU bug.
     */
    public void rebuildSelector() {
        if (!inEventLoop()) {
            execute(new Runnable() {
                @Override
                public void run() {
                    rebuildSelector0();
                }
            });
            return;
        }
        rebuildSelector0();
    }

    @Override
    public int registeredChannels() {
        return selector.keys().size() - cancelledKeys;
    }

    @Override
    public Iterator<Channel> registeredChannelsIterator() {
        assert inEventLoop();
        final Set<SelectionKey> keys = selector.keys();
        if (keys.isEmpty()) {
            return ChannelsReadOnlyIterator.empty();
        }
        return new Iterator<Channel>() {
            final Iterator<SelectionKey> selectionKeyIterator = ObjectUtil.checkNotNull(keys, "selectionKeys").iterator();
            Channel next;
            boolean isDone;

            @Override
            public boolean hasNext() {
                if (isDone) {
                    return false;
                }
                Channel cur = next;
                if (cur == null) {
                    cur = next = nextOrDone();
                    return cur != null;
                }
                return true;
            }

            @Override
            public Channel next() {
                if (isDone) {
                    throw new NoSuchElementException();
                }
                Channel cur = next;
                if (cur == null) {
                    cur = nextOrDone();
                    if (cur == null) {
                        throw new NoSuchElementException();
                    }
                }
                next = nextOrDone();
                return cur;
            }

            @Override
            public void remove() {
                throw new UnsupportedOperationException("remove");
            }

            private Channel nextOrDone() {
                Iterator<SelectionKey> it = selectionKeyIterator;
                while (it.hasNext()) {
                    SelectionKey key = it.next();
                    if (key.isValid()) {
                        Object attachment = key.attachment();
                        if (attachment instanceof AbstractNioChannel) {
                            return (AbstractNioChannel) attachment;
                        }
                    }
                }
                isDone = true;
                return null;
            }
        };
    }

    private void rebuildSelector0() {
        final Selector oldSelector = selector;
        final SelectorTuple newSelectorTuple;

        if (oldSelector == null) {
            return;
        }

        try {
            newSelectorTuple = openSelector();
        } catch (Exception e) {
            logger.warn("Failed to create a new Selector.", e);
            return;
        }

        // Register all channels to the new Selector.
        int nChannels = 0;
        for (SelectionKey key : oldSelector.keys()) {
            Object a = key.attachment();
            try {
                if (!key.isValid() || key.channel().keyFor(newSelectorTuple.unwrappedSelector) != null) {
                    continue;
                }

                int interestOps = key.interestOps();
                key.cancel();
                SelectionKey newKey = key.channel().register(newSelectorTuple.unwrappedSelector, interestOps, a);
                if (a instanceof AbstractNioChannel) {
                    // Update SelectionKey
                    ((AbstractNioChannel) a).selectionKey = newKey;
                }
                nChannels++;
            } catch (Exception e) {
                logger.warn("Failed to re-register a Channel to the new Selector.", e);
                if (a instanceof AbstractNioChannel) {
                    AbstractNioChannel ch = (AbstractNioChannel) a;
                    ch.unsafe().close(ch.unsafe().voidPromise());
                } else {
                    @SuppressWarnings("unchecked") NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
                    invokeChannelUnregistered(task, key, e);
                }
            }
        }

        selector = newSelectorTuple.selector;
        unwrappedSelector = newSelectorTuple.unwrappedSelector;

        try {
            // time to close the old selector as everything else is registered to the new one
            oldSelector.close();
        } catch (Throwable t) {
            if (logger.isWarnEnabled()) {
                logger.warn("Failed to close the old Selector.", t);
            }
        }

        if (logger.isInfoEnabled()) {
            logger.info("Migrated " + nChannels + " channel(s) to the new Selector.");
        }
    }

    /**
     * 在调用io.netty.channel.AbstractChannel.AbstractUnsafe#register()时，
     * EventLoop线程已经启动并执行run()方法，在run()方法里，EventLoop线程会执行一个死循环，直到线程被停止。
     * 在死循环里，
     * EventLoop线程会不断轮询Selector是否有准备就绪的Channel需要处理？
     * taskQueue是否有任务在等待执行？
     * scheduledTaskQueue是否有定时任务需要执行？
     * NioEventLoop.run()是任务处理的关键。
     */
    @Override
    protected void run() {
         /*
            无效空轮询的次数
            JDK的Selector存在Bug，会导致空轮询，CPU飙升。
            Netty会检测Selector.select()空轮询次数，超过SELECTOR_AUTO_REBUILD_THRESHOLD则重建Selector。
            有效轮询:要么有IO事件到达、要么执行了Task。
     */
        int selectCnt = 0;
        for (; ; ) {
            try {
                int strategy;
                try {
                      /*
                            NioEventLoop的执行策略:
                            有任务待执行吗？
                                没有:Selector.select()阻塞，等待IO事件到达(定时任务判断)
                                有:非阻塞调用Selector.selectNow()，
                        */
                    strategy = selectStrategy.calculateStrategy(selectNowSupplier, hasTasks());
                    switch (strategy) {
                        case SelectStrategy.CONTINUE: // 重试IO循环
                            continue;

                        case SelectStrategy.BUSY_WAIT:// NIO不支持忙等，走SELECT
                            // fall-through to SELECT since the busy-wait is not supported with NIO

                        case SelectStrategy.SELECT: // 队列中没有任务要执行
                            // 下一个要执行的定时任务截止时间
                            long curDeadlineNanos = nextScheduledTaskDeadlineNanos();
                            if (curDeadlineNanos == -1L) {
                                curDeadlineNanos = NONE; // nothing on the calendar
                            }
                            nextWakeupNanos.set(curDeadlineNanos);
                            try {
                                  /*
                        如果没有任务要执行，则在下一个任务要执行前，阻塞等待IO事件。
                        没有定时任务，则等待超时为Long.MAX_VALUE,无限等待
                         */
                                if (!hasTasks()) {
                                    // 1、执行一次事件轮询
                                    strategy = select(curDeadlineNanos);
                                }
                            } finally {
                                // This update is just to help block unnecessary selector wakeups
                                // so use of lazySet is ok (no race condition)
                                nextWakeupNanos.lazySet(AWAKE);
                            }
                            // fall through
                        default:
                    }
                } catch (IOException e) {
                    // If we receive an IOException here its because the Selector is messed up. Let's rebuild
                    // the selector and retry. https://github.com/netty/netty/issues/8566
                    rebuildSelector0();
                    selectCnt = 0;
                    handleLoopException(e);
                    continue;
                }

                selectCnt++; //无效轮询次数+1，后面会判断是否重置
                cancelledKeys = 0;
                needsToSelectAgain = false;
                final int ioRatio = this.ioRatio;
                boolean ranTasks;
                if (ioRatio == 100) {
                    // 优先处理所有的IO事件后再去处理Task
                    try {
                        if (strategy > 0) { // 代表有准备就绪的Channel待处理
                            // 2、处理产生IO事件的Channel
                            processSelectedKeys();
                        }
                    } finally {
                        // 3、执行任务
                        // Ensure we always run tasks.                     // 处理完IO事件后，执行所有Task
                        ranTasks = runAllTasks();
                    }
                } else if (strategy > 0) {
                    // 先处理IO事件，并记录所花的时间
                    final long ioStartTime = System.nanoTime();
                    try {
                        processSelectedKeys();
                    } finally {
                        // Ensure we always run tasks.                     // 根据ioTime和ioRatio，计算处理Task能分配的时间
                        final long ioTime = System.nanoTime() - ioStartTime;
                        ranTasks = runAllTasks(ioTime * (100 - ioRatio) / ioRatio);
                    }
                } else {
                       /*
                        有待执行的任务，且Selector.selectNow()返回0，没有IO事件需要处理，那就先执行少量的Task。
                        每64个任务检查一次超时，如果有足够的任务，那么最少执行64个。
                        所以，不应该提交耗时任务，阻塞IO线程！！！
                 */
                    ranTasks = runAllTasks(0); // This will run the minimum number of tasks
                }

                if (ranTasks || strategy > 0) {
                    // 如果执行了任务或者有IO事件，说明这次轮询是有效的，重置selectCnt
                    if (selectCnt > MIN_PREMATURE_SELECTOR_RETURNS && logger.isDebugEnabled()) {
                        logger.debug("Selector.select() returned prematurely {} times in a row for Selector {}.", selectCnt - 1, selector);
                    }
                    selectCnt = 0;
                } else if (unexpectedSelectorWakeup(selectCnt)) { // Unexpected wakeup (unusual case) // 意外唤醒时，是否需要重置selectCnt，解决Selector空轮询Bug
                    selectCnt = 0;
                }
            } catch (CancelledKeyException e) {
                // Harmless exception - log anyway
                if (logger.isDebugEnabled()) {
                    logger.debug(CancelledKeyException.class.getSimpleName() + " raised by a Selector {} - JDK bug?", selector, e);
                }
            } catch (Error e) {
                throw e;
            } catch (Throwable t) {
                handleLoopException(t);
            } finally {
                // 不管正常/异常停止，都要关闭，释放资源。
                // Always handle shutdown even if the loop processing threw an exception.
                try {
                    if (isShuttingDown()) {
                        closeAll();
                        if (confirmShutdown()) {
                            return;
                        }
                    }
                } catch (Error e) {
                    throw e;
                } catch (Throwable t) {
                    handleLoopException(t);
                }
            }
        }
    }

    // returns true if selectCnt should be reset
    private boolean unexpectedSelectorWakeup(int selectCnt) {
        if (Thread.interrupted()) {
            // Thread was interrupted so reset selected keys and break so we not run into a busy loop.
            // As this is most likely a bug in the handler of the user or it's client library we will
            // also log it.
            //
            // See https://github.com/netty/netty/issues/2426
            if (logger.isDebugEnabled()) {
                logger.debug("Selector.select() returned prematurely because " + "Thread.currentThread().interrupt() was called. Use " + "NioEventLoop.shutdownGracefully() to shutdown the NioEventLoop.");
            }
            return true;
        }
        if (SELECTOR_AUTO_REBUILD_THRESHOLD > 0 && selectCnt >= SELECTOR_AUTO_REBUILD_THRESHOLD) {
            // The selector returned prematurely many times in a row.
            // Rebuild the selector to work around the problem.
            logger.warn("Selector.select() returned prematurely {} times in a row; rebuilding Selector {}.", selectCnt, selector);
            rebuildSelector();
            return true;
        }
        return false;
    }

    private static void handleLoopException(Throwable t) {
        logger.warn("Unexpected exception in the selector loop.", t);

        // Prevent possible consecutive immediate failures that lead to
        // excessive CPU consumption.
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            // Ignore.
        }
    }

    private void processSelectedKeys() {
        if (selectedKeys != null) {
            processSelectedKeysOptimized();
        } else {
            processSelectedKeysPlain(selector.selectedKeys());
        }
    }

    @Override
    protected void cleanup() {
        try {
            selector.close();
        } catch (IOException e) {
            logger.warn("Failed to close a selector.", e);
        }
    }

    void cancel(SelectionKey key) {
        key.cancel();
        cancelledKeys++;
        if (cancelledKeys >= CLEANUP_INTERVAL) {
            cancelledKeys = 0;
            needsToSelectAgain = true;
        }
    }

    private void processSelectedKeysPlain(Set<SelectionKey> selectedKeys) {
        // check if the set is empty and if so just return to not create garbage by
        // creating a new Iterator every time even if there is nothing to process.
        // See https://github.com/netty/netty/issues/597
        if (selectedKeys.isEmpty()) {
            return;
        }

        Iterator<SelectionKey> i = selectedKeys.iterator();
        for (; ; ) {
            final SelectionKey k = i.next();
            final Object a = k.attachment();
            i.remove();

            if (a instanceof AbstractNioChannel) {
                processSelectedKey(k, (AbstractNioChannel) a);
            } else {
                @SuppressWarnings("unchecked") NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
                processSelectedKey(k, task);
            }

            if (!i.hasNext()) {
                break;
            }

            if (needsToSelectAgain) {
                selectAgain();
                selectedKeys = selector.selectedKeys();

                // Create the iterator again to avoid ConcurrentModificationException
                if (selectedKeys.isEmpty()) {
                    break;
                } else {
                    i = selectedKeys.iterator();
                }
            }
        }
    }

    private void processSelectedKeysOptimized() {
        for (int i = 0; i < selectedKeys.size; ++i) {
            final SelectionKey k = selectedKeys.keys[i];
            // null out entry in the array to allow to have it GC'ed once the Channel close
            // See https://github.com/netty/netty/issues/2363
            selectedKeys.keys[i] = null;

            final Object a = k.attachment();

            if (a instanceof AbstractNioChannel) {
                processSelectedKey(k, (AbstractNioChannel) a);
            } else {
                @SuppressWarnings("unchecked") NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
                processSelectedKey(k, task);
            }

            if (needsToSelectAgain) {
                // null out entries in the array to allow to have it GC'ed once the Channel close
                // See https://github.com/netty/netty/issues/2363
                selectedKeys.reset(i + 1);

                selectAgain();
                i = -1;
            }
        }
    }

    private void processSelectedKey(SelectionKey k, AbstractNioChannel ch) {
        final AbstractNioChannel.NioUnsafe unsafe = ch.unsafe();
        if (!k.isValid()) {
            final EventLoop eventLoop;
            try {
                eventLoop = ch.eventLoop();
            } catch (Throwable ignored) {
                // If the channel implementation throws an exception because there is no event loop, we ignore this
                // because we are only trying to determine if ch is registered to this event loop and thus has authority
                // to close ch.
                return;
            }
            // Only close ch if ch is still registered to this EventLoop. ch could have deregistered from the event loop
            // and thus the SelectionKey could be cancelled as part of the deregistration process, but the channel is
            // still healthy and should not be closed.
            // See https://github.com/netty/netty/issues/5125
            if (eventLoop == this) {
                // close the channel if the key is not valid anymore
                unsafe.close(unsafe.voidPromise());
            }
            return;
        }

        try {
            int readyOps = k.readyOps();
            // We first need to call finishConnect() before try to trigger a read(...) or write(...) as otherwise
            // the NIO JDK channel implementation may throw a NotYetConnectedException.
            if ((readyOps & SelectionKey.OP_CONNECT) != 0) {
                // remove OP_CONNECT as otherwise Selector.select(..) will always return without blocking
                // See https://github.com/netty/netty/issues/924
                int ops = k.interestOps();
                ops &= ~SelectionKey.OP_CONNECT;
                k.interestOps(ops);

                unsafe.finishConnect();
            }

            // Process OP_WRITE first as we may be able to write some queued buffers and so free memory.
            if ((readyOps & SelectionKey.OP_WRITE) != 0) {
                // Call forceFlush which will also take care of clear the OP_WRITE once there is nothing left to write
                unsafe.forceFlush();
            }

            // Also check for readOps of 0 to workaround possible JDK bug which may otherwise lead
            // to a spin loop
            if ((readyOps & (SelectionKey.OP_READ | SelectionKey.OP_ACCEPT)) != 0 || readyOps == 0) {
                unsafe.read();
            }
        } catch (CancelledKeyException ignored) {
            unsafe.close(unsafe.voidPromise());
        }
    }

    private static void processSelectedKey(SelectionKey k, NioTask<SelectableChannel> task) {
        int state = 0;
        try {
            task.channelReady(k.channel(), k);
            state = 1;
        } catch (Exception e) {
            k.cancel();
            invokeChannelUnregistered(task, k, e);
            state = 2;
        } finally {
            switch (state) {
                case 0:
                    k.cancel();
                    invokeChannelUnregistered(task, k, null);
                    break;
                case 1:
                    if (!k.isValid()) { // Cancelled by channelReady()
                        invokeChannelUnregistered(task, k, null);
                    }
                    break;
                default:
                    break;
            }
        }
    }

    private void closeAll() {
        selectAgain();
        Set<SelectionKey> keys = selector.keys();
        Collection<AbstractNioChannel> channels = new ArrayList<AbstractNioChannel>(keys.size());
        for (SelectionKey k : keys) {
            Object a = k.attachment();
            if (a instanceof AbstractNioChannel) {
                channels.add((AbstractNioChannel) a);
            } else {
                k.cancel();
                @SuppressWarnings("unchecked") NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
                invokeChannelUnregistered(task, k, null);
            }
        }

        for (AbstractNioChannel ch : channels) {
            ch.unsafe().close(ch.unsafe().voidPromise());
        }
    }

    private static void invokeChannelUnregistered(NioTask<SelectableChannel> task, SelectionKey k, Throwable cause) {
        try {
            task.channelUnregistered(k.channel(), cause);
        } catch (Exception e) {
            logger.warn("Unexpected exception while running NioTask.channelUnregistered()", e);
        }
    }

    /**
     * wakenUp表示是否应该唤醒正在阻塞的select操作， 可以看到Netty
     * 在进行一次新的循环逻辑之前， 都会将wakenUp设置成false， 标志新的
     * 一轮循环的开始。 具体的select操作我们也拆分开来看
     *
     * @param inEventLoop
     */
    @Override
    protected void wakeup(boolean inEventLoop) {
        if (!inEventLoop && nextWakeupNanos.getAndSet(AWAKE) != AWAKE) {
            selector.wakeup();
        }
    }

    @Override
    protected boolean beforeScheduledTaskSubmitted(long deadlineNanos) {
        // Note this is also correct for the nextWakeupNanos == -1 (AWAKE) case
        return deadlineNanos < nextWakeupNanos.get();
    }

    @Override
    protected boolean afterScheduledTaskSubmitted(long deadlineNanos) {
        // Note this is also correct for the nextWakeupNanos == -1 (AWAKE) case
        return deadlineNanos < nextWakeupNanos.get();
    }

    Selector unwrappedSelector() {
        return unwrappedSelector;
    }

    int selectNow() throws IOException {
        return selector.selectNow();
    }

    /**
     * @param deadlineNanos
     * @return
     * @throws IOException
     */
    private int select(long deadlineNanos) throws IOException {
        if (deadlineNanos == NONE) {
            return selector.select();
        }
        // Timeout will only be 0 if deadline is within 5 microsecs
        long timeoutMillis = deadlineToDelayNanos(deadlineNanos + 995000L) / 1000000L;
        return timeoutMillis <= 0 ? selector.selectNow() : selector.select(timeoutMillis);
    }

    private void selectAgain() {
        needsToSelectAgain = false;
        try {
            selector.selectNow();
        } catch (Throwable t) {
            logger.warn("Failed to update SelectionKeys.", t);
        }
    }
}
