package io.github.javactrl.rt;

/** 
 * This is a special exception type. It's used to propagate information about 
 * the code execution that is going to be resumed. It propagates in reverse order, 
 * not like usual Java exceptions. So outer blocks will be executed before the inner. 
 * And inner blocks won't be executed if the exception isn't rethrown in any of 
 * its outer blocks.
 * 
 * The object contains how the suspended function on the suspended stack top is to
 * be exited. This can be either exception or a return value. Note also the winding 
 * may not reach this function at all if any of {@link Wind} <code>catch</code> 
 * don't rethrow the object. 
 */
public class Wind extends CThrowable {
  /** 
   * the value to be returned by the suspended function on the stack's top 
   * if {@link tvalue} is {@literal null}
   */
  public Object value;
  /**
   * an exception to throw by the suspended function on the stack's top, 
   * if it isn't {@literal null}
   */
  public Throwable tvalue;

  /**
   * Constructor
   * 
   * @param value the value to return when resuming
   * @param tvalue an exception to throw when resuming (or null if it's a normal return)
   */
  protected Wind(Object value, Throwable tvalue) {
    this.value = value;
    this.tvalue = tvalue;
  }

  /** 
   * This creates a {@link Wind} object to make the suspended function on the 
   * stack's top return the <code>value</code> on successful winding
   * 
   * @param <T> type of the value 
   * @param value value the suspended function will return on successful winding
   * @return exception instance
   */
  public static <T> Wind createReturn(T value) {
    return new Wind(value, null);
  }

  /** 
   * This creates a token to make the suspended function on the stack's top return 
   * the <code>value</code>
   * 
   * @param value an exception to throw at the suspension site
   * @return exception instance
   */
  public static Wind createThrow(Throwable value) {
    return new Wind(null, value);
  }

  /** 
   * This returns {@link value} if {@link tvalue} isn't null or throws {@link tvalue} otherwise
   * 
   * @param <R> type of a result
   * @return resulting value of the suspended site
   * @throws Throwable if <code>tvalue</code> isn't null
   */
  @SuppressWarnings("unchecked")
  public <R> R result() throws Throwable {
    if (tvalue != null)
      throw tvalue;
    return (R)value;
  }

  // /** This casts {@link result} to <code>byte</code> */
  // public <T extends Throwable> byte byteResult() throws T {
  //   return ((Number) result()).byteValue();
  // }

  // /** This casts {@link result} to <code>short</code> */
  // public <T extends Throwable> short shortResult() throws T {
  //   return ((Number) (result())).shortValue();
  // }

  // /** This casts {@link result} to <code>int</code> */
  // public <T extends Throwable> int intResult() throws T {
  //   return ((Number) result()).intValue();
  // }

  // /** This casts {@link result} to <code>long</code> */
  // public <T extends Throwable> long longResult() throws T {
  //   return ((Number) (result())).longValue();
  // }

  // /** This casts {@link result} to <code>float</code> */
  // public <T extends Throwable> float floatResult() throws T {
  //   return ((Number) (result())).floatValue();
  // }

  // /** This casts {@link result} to <code>double</code> */
  // public <T extends Throwable> double doubleResult() throws T {
  //   return ((Number) (result())).doubleValue();
  // }

  // /** This casts {@link result} to <code>boolean</code> */
  // public boolean booleanResult() throws Throwable {
  //   return ((Boolean) (result())).booleanValue();
  // }

  // /** This casts {@link result} to <code>char</code> */
  // public <T extends Throwable> char charResult() throws T {
  //   return ((Character) result()).charValue();
  // }

  // public static Wind valueOf(byte c) {
  //   return new Wind(c, null);
  // }

  // public static Wind valueOf(short c) {
  //   return new Wind(c, null);
  // }

  // public static Wind valueOf(int c) {
  //   return new Wind(c, null);
  // }

  // public static Wind valueOf(long c) {
  //   return new Wind(c, null);
  // }

  // public static Wind valueOf(float c) {
  //   return new Wind(c, null);
  // }

  // public static Wind valueOf(double c) {
  //   return new Wind(c, null);
  // }

  // public static Wind valueOf(boolean c) {
  //   return new Wind(c, null);
  // }

  // public static Wind valueOf(char c) {
  //   return new Wind(c, null);
  // }

  // public static <T> Wind valueOf(T c) {
  //   return new Wind(c, null);
  // }
}
