package com.github.apache9.nedis.util;

import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.FutureListener;
import io.netty.util.concurrent.Promise;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import javax.naming.OperationNotSupportedException;

import com.github.apache9.nedis.AsyncCloseable;
import com.github.apache9.nedis.ConnectionManagement;
import com.github.apache9.nedis.NedisClient;
import com.github.apache9.nedis.NedisClientPool;
import com.github.apache9.nedis.protocol.BlockingListsCommands;
import com.github.apache9.nedis.protocol.TransactionsCommands;

/**
 * @author Apache9
 */
public class NedisUtils {

    public static byte[] toBytes(double value) {
        return toBytes(Double.toString(value));
    }

    public static byte[] toBytes(int value) {
        return toBytes(Integer.toString(value));
    }

    public static byte[] toBytes(long value) {
        return toBytes(Long.toString(value));
    }

    private static final byte[] TRUE = new byte[] {
        '1'
    };

    private static final byte[] FALSE = new byte[] {
        '0'
    };

    public static byte[] toBytes(boolean value) {
        return value ? TRUE : FALSE;
    }

    public static byte[] toBytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    public static String bytesToString(byte[] value) {
        return new String(value, StandardCharsets.UTF_8);
    }

    public static double bytesToDouble(byte[] value) {
        return Double.parseDouble(bytesToString(value));
    }

    public static byte[][] toParamsReverse(byte[][] tailParams, byte[]... headParams) {
        return toParams(headParams, tailParams);
    }

    public static byte[][] toParams(byte[][] headParams, byte[]... tailParams) {
        byte[][] params = Arrays.copyOf(headParams, headParams.length + tailParams.length);
        System.arraycopy(tailParams, 0, params, headParams.length, tailParams.length);
        return params;
    }

    private static EventExecutor getEventExecutor(Future<?> future) {
        Class<?> clazz = future.getClass();
        for (;;) {
            try {
                Method method = clazz.getDeclaredMethod("executor");
                if (method != null) {
                    method.setAccessible(true);
                    return (EventExecutor) method.invoke(future);
                }
            } catch (NoSuchMethodException | SecurityException | IllegalAccessException
                    | InvocationTargetException e) {
                throw new RuntimeException(e);
            }
            clazz = clazz.getSuperclass();
            if (clazz == null) {
                return null;
            }
        }
    }

    private static final class Invoker implements InvocationHandler {

        private final NedisClientPool pool;

        public Invoker(NedisClientPool pool) {
            this.pool = pool;
        }

        private void resetTimeout(final NedisClient client, long previousTimeoutMs) {
            client.setTimeout(previousTimeoutMs).addListener(new FutureListener<Long>() {

                @Override
                public void operationComplete(Future<Long> future) throws Exception {
                    if (future.getNow() != null) {
                        client.release();
                    }
                }

            });
        }

        @SuppressWarnings({
            "unchecked", "rawtypes"
        })
        private void callBlocking(final NedisClient client, Method method, Object[] args,
                final long previousTimeoutMs, final Promise promise) throws IllegalAccessException,
                InvocationTargetException {
            ((Future) method.invoke(client, args)).addListener(new FutureListener() {

                @Override
                public void operationComplete(Future future) throws Exception {
                    if (future.isSuccess()) {
                        promise.trySuccess(future.getNow());
                    } else {
                        promise.tryFailure(future.cause());
                    }
                    resetTimeout(client, previousTimeoutMs);
                }
            });
        }

        private void setInfiniteTimeout(final NedisClient client, final Method method,
                final Object[] args, @SuppressWarnings("rawtypes") final Promise promise) {
            client.setTimeout(0L).addListener(new FutureListener<Long>() {

                @Override
                public void operationComplete(Future<Long> future) throws Exception {
                    // will not fail, but could be null
                    Long previousTimeoutMs = future.get();
                    if (previousTimeoutMs == null) {
                        promise.tryFailure(new IllegalStateException("already closed"));
                    } else {
                        callBlocking(client, method, args, previousTimeoutMs.longValue(), promise);
                    }
                }
            });
        }

        @SuppressWarnings({
            "unchecked", "rawtypes"
        })
        private void call(final NedisClient client, Method method, Object[] args,
                final Promise promise) throws IllegalAccessException, InvocationTargetException {
            ((Future) method.invoke(client, args)).addListener(new FutureListener() {

                @Override
                public void operationComplete(Future future) throws Exception {
                    if (future.isSuccess()) {
                        promise.trySuccess(future.getNow());
                    } else {
                        promise.tryFailure(future.cause());
                    }
                    client.release();
                }
            });
        }

        @Override
        public Object invoke(Object proxy, final Method method, final Object[] args)
                throws Throwable {
            // delegate Object methods like toString, hashCode, etc.
            if (method.getDeclaringClass().equals(Object.class)) {
                return method.invoke(this, args);
            }
            if (method.getDeclaringClass().equals(AsyncCloseable.class)) {
                return method.invoke(pool, args);
            }
            if (method.getDeclaringClass().equals(ConnectionManagement.class)) {
                throw new OperationNotSupportedException(
                        "Can not call connection related methods on pooled client");
            }
            if (method.getDeclaringClass().equals(TransactionsCommands.class)) {
                throw new OperationNotSupportedException(
                        "Can not call transaction related methods on pooled client");
            }
            Future<NedisClient> clientFuture = pool.acquire();
            @SuppressWarnings("rawtypes")
            final Promise promise = getEventExecutor(clientFuture).newPromise();
            clientFuture.addListener(new FutureListener<NedisClient>() {

                @Override
                public void operationComplete(Future<NedisClient> future) throws Exception {
                    if (future.isSuccess()) {
                        if (method.getDeclaringClass().equals(BlockingListsCommands.class)) {
                            setInfiniteTimeout(future.getNow(), method, args, promise);
                        } else {
                            call(future.getNow(), method, args, promise);
                        }
                    } else {
                        promise.tryFailure(future.cause());
                    }
                }
            });
            return promise;
        }

    }

    public static NedisClient newPooledClient(NedisClientPool pool) {
        return (NedisClient) Proxy.newProxyInstance(NedisClient.class.getClassLoader(),
                new Class<?>[] {
                    NedisClient.class
                }, new Invoker(pool));
    }

    public static final Comparator<byte[]> BYTES_COMPARATOR = new Comparator<byte[]>() {

        @Override
        public int compare(byte[] o1, byte[] o2) {
            for (int i = 0, j = 0; i < o1.length && j < o2.length; i++, j++) {
                int a = (o1[i] & 0xff);
                int b = (o2[j] & 0xff);
                if (a != b) {
                    return a - b;
                }
            }
            return o1.length - o2.length;
        }
    };

    public static <T> Map<byte[], T> newBytesKeyMap() {
        return new TreeMap<byte[], T>(BYTES_COMPARATOR);
    }

    public static Set<byte[]> newBytesSet() {
        return new TreeSet<byte[]>(BYTES_COMPARATOR);
    }
}
