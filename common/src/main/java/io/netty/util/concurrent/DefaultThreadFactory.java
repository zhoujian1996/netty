/*
 * Copyright 2013 The Netty Project
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

import io.netty.util.internal.ObjectUtil;
import io.netty.util.internal.StringUtil;

import java.util.Locale;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A {@link ThreadFactory} implementation with a simple naming rule.
 *     * ， 我们分析一下DefaultThreadFactory， 当读者了解了下面这
 *      * 段逻辑之后， 就可以知道Netty关于线程的命令逻辑了。 比如， 为什么
 *      * Netty的线程命名都类似“nioEventLoop-2-3”。
 */
public class DefaultThreadFactory implements ThreadFactory {

    private static final AtomicInteger poolId = new AtomicInteger();

    private final AtomicInteger nextId = new AtomicInteger(); // 线程自增
    private final String prefix; // 线程池里线程的前缀名： nioEventLoopGroup
    private final boolean daemon;
    private final int priority;
    protected final ThreadGroup threadGroup;

    /**
        这里的PoolTYpe是NioEventLoopGroup
     * @param poolType
     */
    public DefaultThreadFactory(Class<?> poolType) {
        this(poolType, false, Thread.NORM_PRIORITY);
    }

    public DefaultThreadFactory(String poolName) {
        this(poolName, false, Thread.NORM_PRIORITY);
    }

    public DefaultThreadFactory(Class<?> poolType, boolean daemon) {
        this(poolType, daemon, Thread.NORM_PRIORITY);
    }

    public DefaultThreadFactory(String poolName, boolean daemon) {
        this(poolName, daemon, Thread.NORM_PRIORITY);
    }

    public DefaultThreadFactory(Class<?> poolType, int priority) {
        this(poolType, false, priority);
    }

    public DefaultThreadFactory(String poolName, int priority) {
        this(poolName, false, priority);
    }

    /**
     *
     * @param poolType
     * @param daemon
     * @param priority
     */
    public DefaultThreadFactory(Class<?> poolType, boolean daemon, int priority) {
        // poolType: class io.netty.channel.nio.NioEventLoopGroup
        // toPoolName(poolType): nioEventLoopGroup
        this(toPoolName(poolType), daemon, priority);
    }

    /**
     * 这里的toPoolName方法的最终结果就是爸NioEventLoopGroup的首字母变成小写，
     * 也就是 nioEventLoopGroup
     * @param poolType
     * @return
     */
    public static String toPoolName(Class<?> poolType) {
        ObjectUtil.checkNotNull(poolType, "poolType");

        String poolName = StringUtil.simpleClassName(poolType);
        switch (poolName.length()) {
            case 0:
                return "unknown";
            case 1:
                return poolName.toLowerCase(Locale.US);
            default:
                if (Character.isUpperCase(poolName.charAt(0)) && Character.isLowerCase(poolName.charAt(1))) {
                    return Character.toLowerCase(poolName.charAt(0)) + poolName.substring(1);
                } else {
                    return poolName;
                }
        }
    }

    /**
     * 省去其他无关信息， 我们看到， 最终DefaultThreadFactory会用一个
     * 成员变量prefix来标记线程名的前缀， 其中poolId是全局的自增ID。 读者
     * 也不难分析， prefix的最终格式为nioEventLoopGroup-线程池编号-。
     * @param poolName
     * @param daemon
     * @param priority
     * @param threadGroup
     */
    public DefaultThreadFactory(String poolName, boolean daemon, int priority, ThreadGroup threadGroup) {
        ObjectUtil.checkNotNull(poolName, "poolName");

        if (priority < Thread.MIN_PRIORITY || priority > Thread.MAX_PRIORITY) {
            throw new IllegalArgumentException(
                    "priority: " + priority + " (expected: Thread.MIN_PRIORITY <= priority <= Thread.MAX_PRIORITY)");
        }

        prefix = poolName + '-' + poolId.incrementAndGet() + '-';
        this.daemon = daemon;
        this.priority = priority;
        this.threadGroup = threadGroup;
    }

    public DefaultThreadFactory(String poolName, boolean daemon, int priority) {
        this(poolName, daemon, priority, null);
    }

    /**
     * 可以看到， 最终创建出来的线程名是prefix加一个自增的nextId。 这
     * 里的nextId是对象级别的成员变量， 只在一个NioEventLoopGroup里递
     * 增。 所以， 我们最终看到， Netty里的线程名字都类似于
     * NioEventLoopGroup-2-3， 表示这个线程是属于第几个
     * NioEventLoopGroup的第几个NioEventLoop
     *
     * 我们还注意到， DefaultThreadFactory创建出来的线程实体是经过
     * Netty优化之后的FastThreadLocalThread， 也可以理解为， 这个类型的线
     * 程实体在操作ThreadLocal的时候， 要比JDK快， 这部分内容的分析， 我
     * 们放到后续章节中
     *
     * @param r
     * @return
     */
    @Override
    public Thread newThread(Runnable r) {
        // 更加清凉的thread
        Thread t = newThread(FastThreadLocalRunnable.wrap(r), prefix + nextId.incrementAndGet());
        try {
            if (t.isDaemon() != daemon) {
                t.setDaemon(daemon);
            }

            if (t.getPriority() != priority) {
                t.setPriority(priority);
            }
        } catch (Exception ignored) {
            // Doesn't matter even if failed to set.
        }
        return t;
    }

    /**
     *
     * @param r
     * @param name
     * @return
     */
    protected Thread newThread(Runnable r, String name) {
        return new FastThreadLocalThread(threadGroup, r, name);
    }
}
