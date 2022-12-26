package io.github.javactrl.ext;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CancellationException;

import io.github.javactrl.rt.CThrowable;
import io.github.javactrl.rt.CallFrame;
import io.github.javactrl.rt.Ctrl;
import io.github.javactrl.rt.Unwind;
import io.github.javactrl.rt.Wind;

/**
 * Various utilities for cooperative concurrency. So <strong>everything here is not (yet?) 
 * thread-safe</strong>. There are many usages of this in preemptive multithreading context, 
 * so probably this will be fixed at some point in the future. 
 */
@Ctrl
public class Concurrency {

  @Ctrl
  private static abstract class JoinImpl<T> implements Serializable {

    final List<CallFrame> suspended = new LinkedList<>();
    final List<? extends CSupplier<? extends T>> threads;
    final Unwind token = new Unwind();
    Wind windToken;
    boolean stopped = false;
    boolean ignoreResult = false;
    RuntimeException ex;

    JoinImpl(List<? extends CSupplier<? extends T>> threads) {
      this.threads = threads;
    }

    abstract boolean isReady();

    abstract Object getResult();

    abstract void setResult(int index, T value);

    private void stop() throws CThrowable {
      stopped = true;
      final var cancelToken = new CancellationException();
      final var toCancel = new LinkedList<>(suspended);
      suspended.clear();
      for (final var i : toCancel) {
        fork(() -> {
          i.resumeThrow(cancelToken);
        });
      }
    }

    @SuppressWarnings({ "unused" })
    void fork(final CRunnable body) throws CThrowable {
      CallFrame current = null;
      try {
        body.run();
      } catch (final Wind w) {
        if (current != null)
          suspended.remove(current);
        throw w;
      } catch (final Unwind u) {
        u.boundary();
        current = u.head;
        if (current != null)
          suspended.add(current);
        return;
      } catch (final Throwable t) {
        if (!isReady())
          ex = t instanceof RuntimeException ? (RuntimeException)t : new RuntimeException(t);
      }
      if (isReady() && !stopped)
        stop();
      if (stopped && suspended.isEmpty()) {
        if (token.head != null) {
          final var frame = token.head;
          token.head = null;
          if (ignoreResult) {
            if (windToken != null)
              frame.wind(windToken);
            return;
          } else {
            frame.wind(ex == null ? Wind.createReturn(getResult()) : Wind.createThrow(ex));
          }
        }
      }
    }

    void join() throws CThrowable {
      for (int i = 0; i < threads.size() && !isReady(); ++i) {
        final var index = i;
        fork(() -> {
          setResult(index, threads.get(index).get());
        });
      }
      if (!suspended.isEmpty()) {
        try {
          Unwind.brk(token);
        } catch (Wind w) {
          if (!stopped) {
            ignoreResult = true;
            stop();
            if (!suspended.isEmpty()) {
              windToken = w;
              token.head = null;
              throw token;
            }
          }
          throw w;
        }
      }
      if (ex != null) {
        throw ex;
      }
    }
  }

  private static class AllOfImpl<T> extends JoinImpl<T> {

    final List<T> result;
    int remaining;

    AllOfImpl(final List<? extends CSupplier<? extends T>> components) {
      super(components);
      remaining = components.size();
      this.result = new ArrayList<T>(Collections.nCopies(remaining, null));
    }

    @Override
    boolean isReady() {
      return remaining == 0 || ex != null;
    }

    @Override
    Object getResult() {
      return result.toArray();
    }

    @Override
    void setResult(final int index, final T value) {
      result.set(index, value);
      --remaining;
    }
  }

  private static class AnyOfImpl<T> extends JoinImpl<T> {
    T result;
    boolean ready = false;

    AnyOfImpl(final List<? extends CSupplier<? extends T>> components) {
      super(components);
    }

    @Override
    boolean isReady() {
      return ready || ex != null;
    }

    @Override
    void setResult(final int index, final T value) {
      if (!ready) {
        ready = true;
        result = value;
      }
    }

    @Override
    Object getResult() {
      return result;
    }

  }

  /**
   * This function runs each item of the `components` list  until 
   * all of them return, or one throws an exception.
   * 
   * If every component returns, it returns a list of the returned values. 
   * If anything throws an exception, this will resume every suspended 
   * component with a throw of <code>java.util.concurrent.CancellationException</code> 
   * once, but it still waits while every component stops either by the returned
   * value of an exception. They both are ignored. If the exception occurs before
   * the next components are applied, they won't be applied. 
   * 
   * 
   * @param <T> type of the resulting value
   * @param components suppliers for resulting item value
   * @return list of resulting values
   * @throws CThrowable if any of the components suspends the whole expression suspends
   */
  public static <T> List<T> allOf(final List<? extends CSupplier<? extends T>> components) throws CThrowable {
    final var state = new AllOfImpl<T>(components);
    state.join();
    return state.result;
  }

  /**
   * A vararg short-cut to {@link #allOf(List)}
   * 
   * @param <T> type of the resulting value
   * @param components suppliers for resulting item value
   * @return list of resulting values
   * @throws CThrowable if any of the components suspends the whole expression suspends
   */
  @SafeVarargs
  public static <T> List<T> allOf(final CSupplier<? extends T>... components) throws CThrowable {
    return allOf(Arrays.asList(components));
  }

  /**
   * This is mostly the same as {@link #allOf(List)} except it stops execution after 
   * any of called components returns a resulting value or throws an exception. 
   * 
   * Like {@link #allOf(List)} it resumes suspended components with CancelationException 
   * and awaits while everything exits either by value or any exception, ignoring them both. 
   * 
   * It returns the first returned value or the first thrown exception.
   * 
   * @param <T> type of resulting value
   * @param components suppliers for resulting item value
   * @return the first returned value
   * @throws CThrowable if any of the components suspends the whole expression suspends
   */
  public static <T> T anyOf(final List<? extends CSupplier<? extends T>> components) throws CThrowable {
    final var state = new AnyOfImpl<T>(components);
    state.join();
    return state.result;
  }

  /**
   * A vararg short-cut to {@link #allOf(List)}
   * 
   * @param <T> type of resulting value
   * @param components suppliers for resulting item value
   * @return the first returned value
   * @throws CThrowable if any of the components suspends the whole expression suspends
   */
  @SafeVarargs
  public static <T> T anyOf(final CSupplier<? extends T>... components) throws CThrowable {
    return anyOf(Arrays.asList(components));
  }
}
