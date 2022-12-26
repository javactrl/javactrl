package io.github.javactrl.ext;

import java.io.Serializable;

import io.github.javactrl.rt.CThrowable;

/**
 * Same as <code>java.util.function.Consumer</code> except it throws {@link CThrowable}
 * 
 * @param <T> type of value the function receives
 */
@FunctionalInterface
public interface CConsumer<T> extends Serializable {
  /**
   * The functional interface function
   * 
   * @param v received value
   * @throws CThrowable if suspended
   */
  void accept(T v) throws CThrowable;
}
