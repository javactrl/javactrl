package io.github.javactrl.rt;

/** Common parent for resumable exceptions */
public class CThrowable extends Throwable {

  /** Constructor */
  public CThrowable() {
    super();
  }

  /**
   * Constructor with a message
   * 
   * @param message message
   */

  public CThrowable(String message) {
    super(message);
  }

  @Override
  public Throwable fillInStackTrace() {
    return this;
  }
}
