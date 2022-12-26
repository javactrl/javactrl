package io.github.javactrl.ext;

import java.io.Serializable;

import io.github.javactrl.rt.CThrowable;
import io.github.javactrl.rt.Unwind;

/**
 * Same as <code>java.lang.Runnable</code> except it throws {@link CThrowable}
 */
@FunctionalInterface
public interface CRunnable extends Serializable {
  /**
   * The functional interface function
   * 
   * @throws CThrowable if suspended
   */
  void run() throws CThrowable;

  /** 
   * This function executes {@link CRunnable} ignoring all {@link CThrowable} exceptions
   * 
   * @param body the code to execute
   */
  static void brackets(CRunnable body) {
    try {
      body.run();
    } catch(Unwind t) {
      t.boundary();
    } catch (CThrowable e) {}
  }
}
