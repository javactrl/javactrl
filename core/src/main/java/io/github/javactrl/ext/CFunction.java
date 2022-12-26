package io.github.javactrl.ext;

import java.io.Serializable;

import io.github.javactrl.rt.CThrowable;

/**
 * Same as <code>java.util.function.Function</code> except it throws {@link CThrowable}
 * 
 * @param <T> argument type
 * @param <R> result type
 */
@FunctionalInterface
public interface CFunction<T,R> extends Serializable {
  /**
   * Functional interface function
   * 
   * @param v the function's argument
   * @return the function's result
   * @throws CThrowable if the function is suspended
   */
  R apply(T v) throws CThrowable;
}
