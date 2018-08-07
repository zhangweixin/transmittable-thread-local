package com.alibaba.ttl;

import com.alibaba.ttl.internal.TtlValue;
import com.alibaba.ttl.internal.TtlValueFactory;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@link TransmittableThreadLocal} can transmit value from the thread of submitting task to the thread of executing task.
 * <p>
 * Note: {@link TransmittableThreadLocal} extends {@link java.lang.InheritableThreadLocal},
 * so {@link TransmittableThreadLocal} first is a {@link java.lang.InheritableThreadLocal}.
 * <p>
 * If you have netty in the runtime and {@link io.netty.util.internal.FastThreadLocal} is supported. You can store
 * {@link TransmittableThreadLocal} in {@link io.netty.util.internal.FastThreadLocal} to give up inheritance for better performance.
 * </p>
 *
 * @author Jerry Lee (oldratlee at gmail dot com)
 * @see TtlRunnable
 * @see TtlCallable
 * @since 0.10.0
 */
public class TransmittableThreadLocal<T> extends InheritableThreadLocal<T> {
    private static final Logger logger = Logger.getLogger(TransmittableThreadLocal.class.getName());

    /**
     * Computes the value for this transmittable thread-local variable
     * as a function of the source thread's value at the time the task
     * Object is created.  This method is called from {@link TtlRunnable} or
     * {@link TtlCallable} when it create, before the task is started.
     * <p>
     * This method merely returns reference of its source thread value, and should be overridden
     * if a different behavior is desired.
     *
     * @since 1.0.0
     */
    protected T copy(T parentValue) {
        return parentValue;
    }

    /**
     * Callback method before task object({@link TtlRunnable}/{@link TtlCallable}) execute.
     * <p>
     * Default behavior is do nothing, and should be overridden
     * if a different behavior is desired.
     * <p>
     * Do not throw any exception, just ignored.
     *
     * @since 1.2.0
     */
    protected void beforeExecute() {
    }

    /**
     * Callback method after task object({@link TtlRunnable}/{@link TtlCallable}) execute.
     * <p>
     * Default behavior is do nothing, and should be overridden
     * if a different behavior is desired.
     * <p>
     * Do not throw any exception, just ignored.
     *
     * @since 1.2.0
     */
    protected void afterExecute() {
    }

    @Override
    public final T get() {
        T value = ttlValue != null ? ttlValue.get() : super.get();
        if (null != value) {
            addValue();
        }
        return value;
    }

    @Override
    public final void set(T value) {
        if (ttlValue != null) {
            ttlValue.set(value);
        } else {
            super.set(value);
        }
        if (null == value) { // may set null to remove value
            removeValue();
        } else {
            addValue();
        }
    }

    @Override
    public final void remove() {
        removeValue();
        if (ttlValue != null) {
            ttlValue.remove();
        } else {
            super.remove();
        }
    }

    private void superRemove() {
        super.remove();
    }

    private T copyValue() {
        return copy(get());
    }

    private final TtlValue<T> ttlValue = TtlValueFactory.create();

    // Note about holder:
    // 1. The value of holder is type Map<TransmittableThreadLocal<?>, ?> (WeakHashMap implementation),
    //    but it is used as *set*.
    // 2. WeakHashMap support null value.
    private static InheritableThreadLocal<Map<TransmittableThreadLocal<?>, ?>> holder =
            new InheritableThreadLocal<Map<TransmittableThreadLocal<?>, ?>>() {
                @Override
                protected Map<TransmittableThreadLocal<?>, ?> initialValue() {
                    return new WeakHashMap<TransmittableThreadLocal<?>, Object>();
                }

                @Override
                protected Map<TransmittableThreadLocal<?>, ?> childValue(Map<TransmittableThreadLocal<?>, ?> parentValue) {
                    return new WeakHashMap<TransmittableThreadLocal<?>, Object>(parentValue);
                }
            };

    private void addValue() {
        if (!holder.get().containsKey(this)) {
            holder.get().put(this, null); // WeakHashMap supports null value.
        }
    }

    private void removeValue() {
        holder.get().remove(this);
    }

    private static void doExecuteCallback(boolean isBefore) {
        for (Map.Entry<TransmittableThreadLocal<?>, ?> entry : holder.get().entrySet()) {
            TransmittableThreadLocal<?> threadLocal = entry.getKey();

            try {
                if (isBefore) {
                    threadLocal.beforeExecute();
                } else {
                    threadLocal.afterExecute();
                }
            } catch (Throwable t) {
                if (logger.isLoggable(Level.WARNING)) {
                    logger.log(Level.WARNING, "TTL exception when " + (isBefore ? "beforeExecute" : "afterExecute") + ", cause: " + t.toString(), t);
                }
            }
        }
    }

    /**
     * Debug only method!
     */
    static void dump(String title) {
        if (title != null && title.length() > 0) {
            System.out.printf("Start TransmittableThreadLocal[%s] Dump...\n", title);
        } else {
            System.out.println("Start TransmittableThreadLocal Dump...");
        }

        for (Map.Entry<TransmittableThreadLocal<?>, ?> entry : holder.get().entrySet()) {
            final TransmittableThreadLocal<?> key = entry.getKey();
            System.out.println(key.get());
        }
        System.out.println("TransmittableThreadLocal Dump end!");
    }

    /**
     * Debug only method!
     */
    static void dump() {
        dump(null);
    }

    /**
     * {@link Transmitter} transmit all {@link TransmittableThreadLocal} values of current thread to
     * other thread by static method {@link #capture()} =&gt; {@link #replay(Object)} =&gt; {@link #restore(Object)} (aka {@code CRR} operation).
     * <p>
     * {@link Transmitter} is <b><i>internal</i></b> manipulation api for <b><i>framework/middleware integration</i></b>;
     * In general, you will <b><i>never</i></b> use it in the <i>biz/application code</i>!
     * <p>
     * Below is the example code:
     *
     * <pre><code>
     * ///////////////////////////////////////////////////////////////////////////
     * // in thread A, capture all TransmittableThreadLocal values of thread A
     * ///////////////////////////////////////////////////////////////////////////
     *
     * Object captured = Transmitter.capture(); // (1)
     *
     * ///////////////////////////////////////////////////////////////////////////
     * // in thread B
     * ///////////////////////////////////////////////////////////////////////////
     *
     * // replay all TransmittableThreadLocal values from thread A
     * Object backup = Transmitter.replay(captured); // (2)
     * try {
     *     // your biz logic, run with the TransmittableThreadLocal values of thread B
     *     System.out.println("Hello");
     *     // ...
     *     return "World";
     * } finally {
     *     // restore the TransmittableThreadLocal of thread B when replay
     *     Transmitter.restore(backup); (3)
     * }
     * </code></pre>
     * <p>
     * see the implementation code of {@link TtlRunnable} and {@link TtlCallable} for more actual code sample.
     * <hr>
     * Of course, {@link #replay(Object)} and {@link #restore(Object)} operation can be simplified
     * by util methods {@link #runCallableWithCaptured(Object, Callable)} or {@link #runSupplierWithCaptured(Object, Supplier)}
     * and the adorable {@code Java 8 lambda syntax}.
     * <p>
     * Below is the example code:
     *
     * <pre><code>
     * ///////////////////////////////////////////////////////////////////////////
     * // in thread A, capture all TransmittableThreadLocal values of thread A
     * ///////////////////////////////////////////////////////////////////////////
     *
     * Object captured = Transmitter.capture(); // (1)
     *
     * ///////////////////////////////////////////////////////////////////////////
     * // in thread B
     * ///////////////////////////////////////////////////////////////////////////
     *
     * String result = runSupplierWithCaptured(captured, () -&gt; {
     *      // your biz logic, run with the TransmittableThreadLocal values of thread A
     *      System.out.println("Hello");
     *      ...
     *      return "World";
     * }); // (2) + (3)
     * </code></pre>
     * <p>
     * The reason of providing 2 util methods is the different {@code throws Exception} type from biz logic({@code lambda}):
     * <ol>
     * <li>{@link #runCallableWithCaptured(Object, Callable)}: No {@code throws}</li>
     * <li>{@link #runSupplierWithCaptured(Object, Supplier)}: {@code throws Exception}</li>
     * </ol>
     * <p>
     * If you has the different {@code throws Exception},
     * you can define your own util method with your own {@code throws Exception} type function interface({@code lambda}).
     *
     * @author Yang Fang (snoop dot fy at gmail dot com)
     * @author Jerry Lee (oldratlee at gmail dot com)
     * @see TtlRunnable
     * @see TtlCallable
     * @since 2.3.0
     */
    public static class Transmitter {
        /**
         * Capture all {@link TransmittableThreadLocal} values in current thread.
         *
         * @return the captured {@link TransmittableThreadLocal} values
         * @since 2.3.0
         */
        public static Object capture() {
            Map<TransmittableThreadLocal<?>, Object> captured = new HashMap<TransmittableThreadLocal<?>, Object>();
            for (TransmittableThreadLocal<?> threadLocal : holder.get().keySet()) {
                captured.put(threadLocal, threadLocal.copyValue());
            }
            return captured;
        }

        /**
         * Replay the captured {@link TransmittableThreadLocal} values from {@link #capture()},
         * and return the backup {@link TransmittableThreadLocal} values in current thread before replay.
         *
         * @param captured captured {@link TransmittableThreadLocal} values from other thread from {@link #capture()}
         * @return the backup {@link TransmittableThreadLocal} values before replay
         * @see #capture()
         * @since 2.3.0
         */
        public static Object replay(Object captured) {
            @SuppressWarnings("unchecked")
            Map<TransmittableThreadLocal<?>, Object> capturedMap = (Map<TransmittableThreadLocal<?>, Object>) captured;
            Map<TransmittableThreadLocal<?>, Object> backup = new HashMap<TransmittableThreadLocal<?>, Object>();

            for (Iterator<? extends Map.Entry<TransmittableThreadLocal<?>, ?>> iterator = holder.get().entrySet().iterator();
                 iterator.hasNext(); ) {
                Map.Entry<TransmittableThreadLocal<?>, ?> next = iterator.next();
                TransmittableThreadLocal<?> threadLocal = next.getKey();

                // backup
                backup.put(threadLocal, threadLocal.get());

                // clear the TTL values only in captured
                // avoid extra TTL values in captured, when run task.
                if (!capturedMap.containsKey(threadLocal)) {
                    iterator.remove();
                    threadLocal.superRemove();
                }
            }

            // set values to captured TTL
            setTtlValuesTo(capturedMap);

            // call beforeExecute callback
            doExecuteCallback(true);

            return backup;
        }

        /**
         * Restore the backup {@link TransmittableThreadLocal} values from {@link Transmitter#replay(Object)}.
         *
         * @param backup the backup {@link TransmittableThreadLocal} values from {@link Transmitter#replay(Object)}
         * @since 2.3.0
         */
        public static void restore(Object backup) {
            @SuppressWarnings("unchecked")
            Map<TransmittableThreadLocal<?>, Object> backupMap = (Map<TransmittableThreadLocal<?>, Object>) backup;
            // call afterExecute callback
            doExecuteCallback(false);

            for (Iterator<? extends Map.Entry<TransmittableThreadLocal<?>, ?>> iterator = holder.get().entrySet().iterator();
                 iterator.hasNext(); ) {
                Map.Entry<TransmittableThreadLocal<?>, ?> next = iterator.next();
                TransmittableThreadLocal<?> threadLocal = next.getKey();

                // clear the TTL values only in backup
                // avoid the extra values of backup after restore
                if (!backupMap.containsKey(threadLocal)) {
                    iterator.remove();
                    threadLocal.superRemove();
                }
            }

            // restore TTL values
            setTtlValuesTo(backupMap);
        }

        private static void setTtlValuesTo(Map<TransmittableThreadLocal<?>, Object> ttlValues) {
            for (Map.Entry<TransmittableThreadLocal<?>, Object> entry : ttlValues.entrySet()) {
                @SuppressWarnings("unchecked")
                TransmittableThreadLocal<Object> threadLocal = (TransmittableThreadLocal<Object>) entry.getKey();
                threadLocal.set(entry.getValue());
            }
        }

        /**
         * Util method for simplifying {@link #replay(Object)} and {@link #restore(Object)} operation.
         *
         * @param captured captured {@link TransmittableThreadLocal} values from other thread from {@link #capture()}
         * @param bizLogic biz logic
         * @param <R>      the return type of biz logic
         * @return the return value of biz logic
         * @see #capture()
         * @see #replay(Object)
         * @see #restore(Object)
         * @since 2.3.1
         */
        public static <R> R runSupplierWithCaptured(Object captured, Supplier<R> bizLogic) {
            Object backup = replay(captured);
            try {
                return bizLogic.get();
            } finally {
                restore(backup);
            }
        }

        /**
         * Util method for simplifying {@link #replay(Object)} and {@link #restore(Object)} operation.
         *
         * @param captured captured {@link TransmittableThreadLocal} values from other thread from {@link #capture()}
         * @param bizLogic biz logic
         * @param <R>      the return type of biz logic
         * @return the return value of biz logic
         * @throws Exception exception threw by biz logic
         * @see #capture()
         * @see #replay(Object)
         * @see #restore(Object)
         * @since 2.3.1
         */
        public static <R> R runCallableWithCaptured(Object captured, Callable<R> bizLogic) throws Exception {
            Object backup = replay(captured);
            try {
                return bizLogic.call();
            } finally {
                restore(backup);
            }
        }

        private Transmitter() {
            throw new InstantiationError("Must not instantiate this class");
        }
    }
}
