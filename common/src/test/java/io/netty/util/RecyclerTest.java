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
package io.netty.util;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class RecyclerTest {

    private static Recycler<HandledObject> newRecycler(int max) {
        return new Recycler<HandledObject>(max) {
            @Override
            protected HandledObject newObject(
                    Recycler.Handle<HandledObject> handle) {
                return new HandledObject(handle);
            }
        };
    }

    @Test(expected = IllegalStateException.class)
    public void testMultipleRecycle() {
        Recycler<HandledObject> recycler = newRecycler(1024);
        HandledObject object = recycler.get();
        object.recycle();
        object.recycle();//重复回收时会报异常
    }

    @Test
    public void testRecycle() {
        //回收的对象在被取出后，还可以再次被回收
        Recycler<HandledObject> recycler = newRecycler(1024);
        HandledObject object = recycler.get();
        object.recycle();
        HandledObject object2 = recycler.get();
        assertSame(object, object2);
        object2.recycle();
    }

    @Test
    public void testRecycleDisable() {
        Recycler<HandledObject> recycler = newRecycler(-1);
        HandledObject object = recycler.get();
        object.recycle();
        HandledObject object2 = recycler.get();
        assertNotSame(object, object2);
        object2.recycle();
    }

    /**
     * Test to make sure bug #2848 never happens again
     * https://github.com/netty/netty/issues/2848
     */
    @Test
    public void testMaxCapacity() {
        testMaxCapacity(300);
        Random rand = new Random();
        for (int i = 0; i < 50; i++) {
            testMaxCapacity(rand.nextInt(1000) + 256); // 256 - 1256
        }
    }

    private static void testMaxCapacity(int maxCapacity) {
        Recycler<HandledObject> recycler = newRecycler(maxCapacity);
        HandledObject[] objects = new HandledObject[maxCapacity * 3];
        for (int i = 0; i < objects.length; i++) {
            objects[i] = recycler.get();
        }

        for (int i = 0; i < objects.length; i++) {
            objects[i].recycle();
            objects[i] = null;
        }

        assertTrue("The threadLocalCapacity (" + recycler.threadLocalCapacity() + ") must be <= maxCapacity ("
                + maxCapacity + ") as we not pool all new handles internally",
                maxCapacity >= recycler.threadLocalCapacity());
    }

    @Test
    public void testRecycleAtDifferentThread() throws Exception {
        final Recycler<HandledObject> recycler = new Recycler<HandledObject>(256, 10, 2, 10) {
            @Override
            protected HandledObject newObject(Recycler.Handle<HandledObject> handle) {
                return new HandledObject(handle);
            }
        };

        final HandledObject o = recycler.get();
        final HandledObject o2 = recycler.get();
        final Thread thread = new Thread() {
            @Override
            public void run() {
                o.recycle();
                o2.recycle();
            }
        };
        thread.start();
        thread.join();

        assertSame(recycler.get(), o);
        assertNotSame(recycler.get(), o2);
    }

    @Test
    public void testDuplicateRecycleInDiffThread() throws InterruptedException{
        final CountDownLatch c = new CountDownLatch(1);
        final Recycler<HandledObject> recycler = newRecycler(1024);
        final HandledObject obj = recycler.get();
        obj.recycle();
        assertSame(obj, recycler.get()); //这里获得的是上一步回收的
        assertNotSame(obj, recycler.get()); //之前回收的已经被弹出, 这里get()拿到的是新创建的
        new Thread(){
            //线程A
            @Override
            public void run() {
                obj.recycle(); // 这一步回收只会在[main]线程绑定的stack里的 WeakOrderQueue 里塞一个对象
                HandledObject obj2 = recycler.get(); //线程A对应的stack是空的,也没有线程回收线程A创建的对象（WeakOrderQueue也是空的），
                assertNotSame(obj, obj2); //obj2 也是新创建的
                c.countDown();
            }
        }.start();

        c.await(); //确保线程A跑完
        HandledObject obj3 = recycler.get();
        assertSame(obj, obj3);
    }

    //主要测试被另一个线程回收后，原有 stack 的变化
    @Test(expected = IllegalStateException.class)
    public void testDuplicateRecycleInDiffThread2() throws InterruptedException{
        final CountDownLatch c = new CountDownLatch(1);
        final Recycler<HandledObject> recycler = newRecycler(1024);
        final HandledObject obj = recycler.get();
        obj.recycle();  //往 stack 里塞了一个handle, handle.stack != null
        new Thread(){
            //线程A
            @Override
            public void run() {
                //这里会将obj.handler.stack=null
                //但是main线程对应stack里还是持有obj的
                //这步会导致 handle.recycleId != handle.lastRecycleId
                obj.recycle(); // 这一步回收只会在[main]线程绑定的stack里的 WeakOrderQueue 里塞一个对象

                c.countDown();
            }
        }.start();

        c.await(); //确保线程A跑完
        HandledObject obj3 = recycler.get(); //由于andle.recycleId != handle.lastRecycleId 抛 "recycled multiple times" 异常
        assertSame(obj, obj3);

        //一个对象被不同线程回收后，在get()时会抛异常，实际应用场景中，怎样避免一个对象被重复回收呢
    }

    @Test
    public void testMaxCapacityWithRecycleAtDifferentThread() throws Exception {
        final int maxCapacity = 4; // Choose the number smaller than WeakOrderQueue.LINK_CAPACITY
        final Recycler<HandledObject> recycler = newRecycler(maxCapacity);

        // Borrow 2 * maxCapacity objects.
        // Return the half from the same thread.
        // Return the other half from the different thread.

        final HandledObject[] array = new HandledObject[maxCapacity * 3];
        for (int i = 0; i < array.length; i ++) {
            array[i] = recycler.get();
        }

        for (int i = 0; i < maxCapacity; i ++) {
            array[i].recycle();
        }

        final Thread thread = new Thread() {
            @Override
            public void run() {
                for (int i = maxCapacity; i < array.length; i ++) {
                    array[i].recycle();
                }
            }
        };
        thread.start();
        thread.join();

        assertEquals(maxCapacity, recycler.threadLocalCapacity());
        assertEquals(1, recycler.threadLocalSize());

        for (int i = 0; i < array.length; i ++) {
            recycler.get();
        }

        assertEquals(maxCapacity, recycler.threadLocalCapacity());
        assertEquals(0, recycler.threadLocalSize());
    }

    @Test
    public void testDiscardingExceedingElementsWithRecycleAtDifferentThread() throws Exception {
        final int maxCapacity = 32;
        final AtomicInteger instancesCount = new AtomicInteger(0);

        final Recycler<HandledObject> recycler = new Recycler<HandledObject>(maxCapacity, 2) {
            @Override
            protected HandledObject newObject(Recycler.Handle<HandledObject> handle) {
                instancesCount.incrementAndGet();
                return new HandledObject(handle);
            }
        };

        // Borrow 2 * maxCapacity objects.
        final HandledObject[] array = new HandledObject[maxCapacity * 2];
        for (int i = 0; i < array.length; i++) {
            array[i] = recycler.get();
        }

        assertEquals(array.length, instancesCount.get());
        // Reset counter.
        instancesCount.set(0);

        // Recycle from other thread.
        final Thread thread = new Thread() {
            @Override
            public void run() {
                for (HandledObject object: array) {
                    object.recycle();
                }
            }
        };
        thread.start();
        thread.join();

        assertEquals(0, instancesCount.get());

        // Borrow 2 * maxCapacity objects. Half of them should come from
        // the recycler queue, the other half should be freshly allocated.
        for (int i = 0; i < array.length; i++) {
            recycler.get();
        }

        // The implementation uses maxCapacity / 2 as limit per WeakOrderQueue
        assertTrue("The instances count (" +  instancesCount.get() + ") must be <= array.length (" + array.length
                + ") - maxCapacity (" + maxCapacity + ") / 2 as we not pool all new handles" +
                " internally", array.length - maxCapacity / 2 <= instancesCount.get());
    }

    static final class HandledObject {
        Recycler.Handle<HandledObject> handle;
        static final AtomicInteger ID = new AtomicInteger(1);
        final Integer tag;

        HandledObject(Recycler.Handle<HandledObject> handle) {
            this.handle = handle;
            this.tag = ID.getAndIncrement();
        }

        void recycle() {
            handle.recycle(this);
        }
        @Override
        public String toString() {
            return "HandleObject:" + tag;
        }
    }

    @Test
    public void tessRatioMask(){
        int ratioMask = 8 - 1;
        for(int i=0; i<100; i++){
            System.out.println( i + " : " + ((i & ratioMask)));
        }
    }

    //测试回收对象的概率
    @Test
    public void testRatioMask2(){
        Recycler<HandledObject> recycler = newRecycler(1024);
        List<HandledObject> list = new ArrayList();
        for(int i=1; i < 9; i++){
            HandledObject obj = recycler.get();
            System.out.println("first time get obj:" + obj);
            list.add(obj);
        }
        for(HandledObject obj : list){
            obj.recycle();
        }
        //可以看出之前的8个对象只有一个被回收放入对象池
        for(int i=1; i < 9; i++){
            HandledObject obj = recycler.get();
            System.out.println(obj);
        }
    }
}
