package io.github.javactrl.rt;

/** 
 * This is a special exception type. An execution can be continued after its 
 * (or its descendant) <code>throw</code> statement. 
 */
public class Unwind extends CThrowable {

  /** some value the <code>throw</code> side sends to <code>catch</code> sides */
  public Object payload;

  /** last current captured call frame */
  public CallFrame head;

  /** constructor with {@literal null} payload */
  public Unwind() {
    this(null);
  }

  /**
   * Constructor initializing a payload
   * 
   * @param <T> type of payload (stored as Object anyway) to be used somehow by some <code>catch</code> handler
   * @param payload value to be used somehow in <code>catch</code> handlers
   */
  public <T> Unwind(T payload) {
    this.payload = payload;
  }

  /**
   * This just throws <code>u</code>. 
   * 
   * This function is needed because in Java, <code>throw</code> is a statement, so it cannot return 
   * anything. So the instrument doesn't even try to transform throws. Instead, `throw` 
   * statements must be inside such functions as this. This also suppresses unreachable 
   * code warnings.
   * 
   * If a call can be resumed with a checked exception, it's enough to add a similar function 
   * but with a corresponding `throws` declaration.
   * 
   * @param <R> type of value received when resuming
   * @param u an exception object used to unwind the stack until not rethrowing <code>catch</code>
   * @throws CThrowable always when called
   * @return value received when resuming
   */
  public static <R> R brk(Unwind u) throws CThrowable {
    throw u;
  }

  /** 
   * a short-cut for {@link brk} creating an exception object 
   * 
   * @param <R> type of value received when resuming
   * @param <T> type of payload value to be used somehow by some <code>catch</code> handler
   * @param payload value to be used somehow by some <code>catch</code> handler
   * @throws CThrowable always when called
   * @return value received when resuming
   */
  public static <R, T> R brkValue(T payload) throws CThrowable {
    throw new Unwind(payload);
  }

  /** 
   * This is just a function to override if needed, it can be used to convey some data back 
   * to the throw point without winding. It's responsibility of the <code>catch</code>
   * block to call it.
   */
  public void boundary() { }

}
