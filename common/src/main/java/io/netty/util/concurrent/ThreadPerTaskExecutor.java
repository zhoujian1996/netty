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

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;

/**
 * 这个类的作用是， 每次执行execute方法的时候， 都会调用
 * threadFactory来创建一个线程， 把需要执行的命令传递进去， 然后执
 * 行。
 */
public final class ThreadPerTaskExecutor implements Executor {
    private final ThreadFactory threadFactory;

    public ThreadPerTaskExecutor(ThreadFactory threadFactory) {
        this.threadFactory = ObjectUtil.checkNotNull(threadFactory, "threadFactory");
    }

    /**
     *  /*
     *     执行任务时，利用ThreadFactory创建一个新线程去跑。
     *     EventLoop会在第一次execute()时调用该方法，整个生命周期只会调用一次，
     *     即每个EventLoop只会创建一个线程，后续所有的任务，都是在run()方法里无限轮询去执行。
     *      */
    @Override
    public void execute(Runnable command) {
        threadFactory.newThread(command).start();
    }
}
