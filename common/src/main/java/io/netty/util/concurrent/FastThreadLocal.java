/*
 * Copyright 2014 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.util.concurrent;

import io.netty.util.internal.InternalThreadLocalMap;
import io.netty.util.internal.PlatformDependent;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * A special variant of {@link ThreadLocal} that yields higher access performance when accessed from a
 * {@link FastThreadLocalThread}.
 * <p>
 * Internally, a {@link FastThreadLocal} uses a constant index in an array, instead of using hash code and hash table,
 * to look for a variable.  Although seemingly very subtle, it yields slight performance advantage over using a hash
 * table, and it is useful when accessed frequently.
 * </p><p>
 * To take advantage of this thread-local variable, your thread must be a {@link FastThreadLocalThread} or its subtype.
 * By default, all threads created by {@link DefaultThreadFactory} are {@link FastThreadLocalThread} due to this reason.
 * </p><p>
 * Note that the fast path is only possible on threads that extend {@link FastThreadLocalThread}, because it requires
 * a special field to store the necessary state.  An access by any other kind of thread falls back to a regular
 * {@link ThreadLocal}.
 * </p>
 *
 * @param <V> the type of the thread-local variable
 * @see ThreadLocal
 *
 * 该类被设计作为 ThreadLocal 的变种, 当从 FastThreadLocalThread 中访问对象时，会带来一定的性能优化
 * 在访问一个变量时， FastThreadLocal 使用一个索引直接从数组中获取，而不用通过转换hashCode从hash table中寻找
 * 当访问非常频繁当时候, 这个细微的变化会带来不错的性能提升
 * 要使用这个特性，你必须使用 FastThreadLocalThread (DefaultThreadFactory 创建的线程都是该类型的)
 * 当通过其他线程访问时，将使用原始的 ThreadLocal
 */
public class FastThreadLocal<V> {

    /**
     * index : 总是为 0, 数组里的第一个索引里存的是 Set<FastThreadLocal> - 对应所有的
     */
    private static final int variablesToRemoveIndex = InternalThreadLocalMap.nextVariableIndex();

    /**
     * Removes all {@link FastThreadLocal} variables bound to the current thread.  This operation is useful when you
     * are in a container environment, and you don't want to leave the thread local variables in the threads you do not
     * manage.
     * 用来回收所有绑定在当前线程的所有 FastThreadLocal
     * 当在容器环境中非常受用 - 当你不想保留一些变量在线程中
     */
    public static void removeAll() {
        InternalThreadLocalMap threadLocalMap = InternalThreadLocalMap.getIfSet();
        if (threadLocalMap == null) {
            return;
        }

        try {
            Object v = threadLocalMap.indexedVariable(variablesToRemoveIndex);
            if (v != null && v != InternalThreadLocalMap.UNSET) {
                @SuppressWarnings("unchecked")
                Set<FastThreadLocal<?>> variablesToRemove = (Set<FastThreadLocal<?>>) v;
                FastThreadLocal<?>[] variablesToRemoveArray =
                        variablesToRemove.toArray(new FastThreadLocal[variablesToRemove.size()]);
                for (FastThreadLocal<?> tlv: variablesToRemoveArray) {
                    tlv.remove(threadLocalMap);
                }
            }
        } finally {
            InternalThreadLocalMap.remove();
        }
    }

    /**
     * Returns the number of thread local variables bound to the current thread.
     */
    public static int size() {
        InternalThreadLocalMap threadLocalMap = InternalThreadLocalMap.getIfSet();
        if (threadLocalMap == null) {
            return 0;
        } else {
            return threadLocalMap.size();
        }
    }

    /**
     * Destroys the data structure that keeps all {@link FastThreadLocal} variables accessed from
     * non-{@link FastThreadLocalThread}s.  This operation is useful when you are in a container environment, and you
     * do not want to leave the thread local variables in the threads you do not manage.  Call this method when your
     * application is being unloaded from the container.
     *
     * 当在非FastThreadLocalThread 线程里使用 FastThreadLocal 时，不想保留存储的变量时使用该方法
     */
    public static void destroy() {
        InternalThreadLocalMap.destroy();
    }

    /**
     * 添加变量, 当之前尚未存储任何变量时, 则创建一个 Set<FastThreadLocal<?>>
     * 注意:
     *   1. IdentityHashMap<FastThreadLocal<?>, Boolean> 也是一个 Map, 特性时，2个key是否相同的判断条件为 key1=key2，
     *     非 key1.equals(key2)
     *   2. Collections.newSetFromMap(Map) 返回的是个包装类:SetFromMap, 它也是 Set，
     *      SetFromMap 的特性是它具备它所包装 Map 的属性, ex: 如果Map是ConcurrentHashMap，那么这个Set就具备并发安全性的特性，
     *      因此这里的 Set 具备 IdentityMap 的特性，也就是元素相同的条件为 key1==key2
     *
     * variablesToRemove 底层是一个包装了 IdentityMap 的 SetFromMap
     * 那么, 为什么不用 HashSet 呢 ????
     */
    @SuppressWarnings("unchecked")
    private static void addToVariablesToRemove(InternalThreadLocalMap threadLocalMap, FastThreadLocal<?> variable) {
        Object v = threadLocalMap.indexedVariable(variablesToRemoveIndex);
        Set<FastThreadLocal<?>> variablesToRemove;
        if (v == InternalThreadLocalMap.UNSET || v == null) {
            variablesToRemove = Collections.newSetFromMap(new IdentityHashMap<FastThreadLocal<?>, Boolean>());
            threadLocalMap.setIndexedVariable(variablesToRemoveIndex, variablesToRemove);
        } else {
            variablesToRemove = (Set<FastThreadLocal<?>>) v;
        }

        variablesToRemove.add(variable);
    }

    private static void removeFromVariablesToRemove(
            InternalThreadLocalMap threadLocalMap, FastThreadLocal<?> variable) {

        Object v = threadLocalMap.indexedVariable(variablesToRemoveIndex);

        if (v == InternalThreadLocalMap.UNSET || v == null) {
            return;
        }

        @SuppressWarnings("unchecked")
        Set<FastThreadLocal<?>> variablesToRemove = (Set<FastThreadLocal<?>>) v;
        variablesToRemove.remove(variable);
    }

    // 唯一 index
    private final int index;

    public FastThreadLocal() {
        index = InternalThreadLocalMap.nextVariableIndex();
    }

    /**
     * Returns the current value for the current thread
     */
    public final V get() {
        return get(InternalThreadLocalMap.get());
    }

    /**
     * Returns the current value for the specified thread local map.
     * The specified thread local map must be for the current thread.
     * 个人认为, 这个方法用 'public' 开放出来不合理
     */
    @SuppressWarnings("unchecked")
    public final V get(InternalThreadLocalMap threadLocalMap) {
        Object v = threadLocalMap.indexedVariable(index);
        if (v != InternalThreadLocalMap.UNSET) {
            return (V) v;
        }

        return initialize(threadLocalMap);
    }

    private V initialize(InternalThreadLocalMap threadLocalMap) {
        V v = null;
        try {
            v = initialValue();
        } catch (Exception e) {
            PlatformDependent.throwException(e);
        }

        threadLocalMap.setIndexedVariable(index, v);
        addToVariablesToRemove(threadLocalMap, this);
        return v;
    }

    /**
     * Set the value for the current thread.
     */
    public final void set(V value) {
        if (value != InternalThreadLocalMap.UNSET) {
            set(InternalThreadLocalMap.get(), value);
        } else {
            remove();
        }
    }

    /**
     * Set the value for the specified thread local map. The specified thread local map must be for the current thread.
     * @param threadLocalMap 按照上面的翻译来看, 这个localMap 必须是当前线程的
     * 该方法目前的调用放就2处, 一个是上面的 set(V), 另一个调用是ChannelOutboundBuffer.nioBuffers()(和set(V)一个语意)
     * 个人认为, 这个方法用 'public' 开放出来不合理
     */
    public final void set(InternalThreadLocalMap threadLocalMap, V value) {
        if (value != InternalThreadLocalMap.UNSET) {
            if (threadLocalMap.setIndexedVariable(index, value)) {
                addToVariablesToRemove(threadLocalMap, this);
            }
        } else {
            remove(threadLocalMap);
        }
    }

    /**
     * Returns {@code true} if and only if this thread-local variable is set.
     */
    public final boolean isSet() {
        return isSet(InternalThreadLocalMap.getIfSet());
    }

    /**
     * Returns {@code true} if and only if this thread-local variable is set.
     * The specified thread local map must be for the current thread.
     */
    public final boolean isSet(InternalThreadLocalMap threadLocalMap) {
        return threadLocalMap != null && threadLocalMap.isIndexedVariableSet(index);
    }
    /**
     * Sets the value to uninitialized; a proceeding call to get() will trigger a call to initialValue().
     */
    public final void remove() {
        remove(InternalThreadLocalMap.getIfSet());
    }

    /**
     * Sets the value to uninitialized for the specified thread local map;
     * a proceeding call to get() will trigger a call to initialValue().
     * The specified thread local map must be for the current thread.
     */
    @SuppressWarnings("unchecked")
    public final void remove(InternalThreadLocalMap threadLocalMap) {
        if (threadLocalMap == null) {
            return;
        }

        //这里为什么不在同一个方法里,做这2步操作呢?
        //从 indexedVariables 移除 FastThreadLocal 和 其对应的 value, 应该是同一件'事'
        Object v = threadLocalMap.removeIndexedVariable(index);
        removeFromVariablesToRemove(threadLocalMap, this);

        if (v != InternalThreadLocalMap.UNSET) {
            try {
                onRemoval((V) v);
            } catch (Exception e) {
                PlatformDependent.throwException(e);
            }
        }
    }

    /**
     * Returns the initial value for this thread-local variable.
     */
    protected V initialValue() throws Exception {
        return null;
    }

    /**
     * Invoked when this thread local variable is removed by {@link #remove()}.
     */
    protected void onRemoval(@SuppressWarnings("UnusedParameters") V value) throws Exception { }
}
