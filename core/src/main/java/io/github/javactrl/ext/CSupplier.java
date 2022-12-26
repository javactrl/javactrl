package io.github.javactrl.ext;

import java.io.Serializable;

import io.github.javactrl.rt.CThrowable;

/**
 * Same as <code>java.util.function.Consumer</code> except it throws {@link CThrowable}
 * 
 * @param <T> result type
 */
@FunctionalInterface
public interface CSupplier<T> extends Serializable {
  /**
   * The functional interface function
   * 
   * @return resulting value
   * @throws CThrowable if suspended
   */
  T get() throws CThrowable;
}
