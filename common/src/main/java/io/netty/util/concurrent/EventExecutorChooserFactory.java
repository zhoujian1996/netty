/*
 * Copyright 2016 The Netty Project
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

import io.netty.util.internal.UnstableApi;

/**
 * Factory that creates new {@link EventExecutorChooser}s.
 * 在传统的NIO编程中， 一个新连接被创建后， 通常需要给这个连接
 * 绑定一个Selector， 之后这个连接的整个生命周期都由这个Selector管
 * 理。 而从上面的代码中， 我们分析到， Netty中一个Selector对应一个
 * NioEventLoop， 线程选择器的作用正是为一个连接在一个
 * EventLoopGroup中选择一个NioEventLoop， 从而将这个连接绑定到某个
 * Selector上
 */
@UnstableApi
public interface EventExecutorChooserFactory {

    /**
     * Returns a new {@link EventExecutorChooser}.
     */
    EventExecutorChooser newChooser(EventExecutor[] executors);

    /**
     * Chooses the next {@link EventExecutor} to use.
     */
    @UnstableApi
    interface EventExecutorChooser {

        /**
         * Returns the new {@link EventExecutor} to use.
         */
        EventExecutor next();
    }
}
