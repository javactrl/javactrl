package io.github.javactrl.rt;

import java.io.IOException;
import java.io.NotSerializableException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import static java.lang.String.format;

/**
 * This class is a representation of a call stack frame.
 * 
 * Every method starting with "_" is supposed to be called from the instrumented code.
 */
@SuppressWarnings("UseSpecificCatch")
public class CallFrame implements Cloneable, Serializable {

  /** 
   * Each function supporting continuations is converted into a lambda with this interface
   * 
   * 
   * <strong>This should be used only be generated code and not directly</strong>
   * 
   */
  @FunctionalInterface
  public interface _Handler {
    /***
     * Executes next continuation step
     * 
     * @param frame the current call frame
     * @param ints <code>int</code> locals or null if there no such
     * @param longs <code>long</code> locals or null if there no such
     * @param floats <code>float</code> locals or null if there no such
     * @param doubles <code>double</code> locals or null if there no such
     * @param refs reference locals or null if there no such
     * @return the function's result
     * @throws CThrowable if the execution is suspended
     */
    Object run(CallFrame frame, int[] ints, long[] longs, float[] floats, double[] doubles, Object[] refs)
        throws CThrowable;
  }

  /** common serialVersionUID */
  public static final long serialVersionUID = 1L;
  /** owner class */
  public Class<?> owner;
  /** unique (within the owner class) method id */
  public String methodName;
  /** current next frame, called by this frame */
  public CallFrame next;
  /** current state id */
  public int state = 0;
  /** the body of the function */
  public _Handler handler;
  /** <code>int</code> local and stack variables */
  public int[] vI;
  /** <code>long</code> local and stack variables */
  public long[] vJ;
  /** <code>float</code> local and stack variables */
  public float[] vF;
  /** <code>double</code> local and stack variables */
  public double[] vD;
  /** local and stack reference variables  */
  public Object[] v;

  /** currently processing winding exception object  */
  private Wind token;
  /** current stage 0, 1 or 2 */
  private int windStage;
  /** calculating current wind <code>catch</code> handlers number */
  private int windCount;
  /** currently invoking wind <code>catch</code> handlers position */
  private int windIter;

  /** set this to `true` for serialization debugging */
  public static boolean TRACE_SERIALIZATION = false;

  /**
   * Call frame constructor
   * 
   * @param owner class of the called method
   * @param methodName name of the method
   * @param intMax number of <code>int</code> variables
   * @param longMax number of <code>long</code> variables
   * @param floatMax number of <code>float</code> variables
   * @param doubleMax number of <code>double</code> variables
   * @param refMax number of reference variables
   */
  public CallFrame(final Class<?> owner, final String methodName, final int intMax, final int longMax,
      final int floatMax, final int doubleMax, final int refMax) {
    this.owner = owner;
    this.methodName = methodName;
    if (intMax > 0)
      vI = new int[intMax];
    if (longMax > 0)
      vJ = new long[longMax];
    if (floatMax > 0)
      vF = new float[floatMax];
    if (doubleMax > 0)
      vD = new double[doubleMax];
    if (refMax > 0)
      v = new Object[refMax];
  }

  /** 
   * used by the generated code to create an instance of this class 
   * 
   * @param owner the method's owner class
   * @param methodName method's name
   * @param intMax number of <code>int</code> variables
   * @param longMax number of <code>long</code> variables
   * @param floatMax number of <code>float</code> variables
   * @param doubleMax number of <code>double</code> variables
   * @param refMax number of reference variables
   * @return a call frame instance
   */
  public static CallFrame _create(final Class<?> owner, final String methodName, final int intMax, final int longMax,
      final int floatMax, final int doubleMax, final int refMax) {
    return new CallFrame(owner, methodName, intMax, longMax, floatMax, doubleMax, refMax);
  }

  /**
   * Serializable read implementation
   * 
   * @param stream input stream
   * @throws ClassNotFoundException if corresponding class isn't loaded
   * @throws IOException on IO errors
   */
  private void readObject(final ObjectInputStream stream) throws ClassNotFoundException, IOException {
    owner = (Class<?>) stream.readObject();
    methodName = (String) stream.readObject();
    state = stream.readInt();
    vI = (int[]) stream.readObject();
    vJ = (long[]) stream.readObject();
    vF = (float[]) stream.readObject();
    vD = (double[]) stream.readObject();
    v = (Object[]) stream.readObject();
    next = (CallFrame) stream.readObject();
    /*
     * the javac generated `$deserializeLambda$` function doesn't work, handler's
     * lambda is generated on
     * instrumentation stage, so we have to use an own one, but the method name is
     * enough to recover it
     */
    try {
      final var method = owner.getDeclaredMethod(methodName + "$cc$lambda");
      method.setAccessible(true);
      handler = (_Handler) method.invoke(null);
    } catch (Throwable e) {
      throw new RuntimeException("couldn't read CC handler", e);
    }
  }

  /**
   * Serializable write implementation
   * 
   * @param stream output stream
   * @throws IOException on IO errors
   */
  private void writeObject(final ObjectOutputStream stream) throws IOException {
    stream.writeObject(owner);
    stream.writeObject(methodName);
    stream.writeInt(state);
    stream.writeObject(vI);
    stream.writeObject(vJ);
    stream.writeObject(vF);
    stream.writeObject(vD);
    try {
      stream.writeObject(v);
    } catch (NotSerializableException e) {
      if (TRACE_SERIALIZATION)
        System.err.println(format("Not serializable object in %s.%s", owner.getName(), methodName));
      throw e;
    }
    stream.writeObject(next);
  }

  @SuppressWarnings("unchecked")
  private <R> R windSelf(Wind wind) throws CThrowable {
    token = wind;
    windStage = 0;
    windCount = 0;
    try {
      return (R) handler.run(this, vI, vJ, vF, vD, v);
    } catch (final Wind otherWind) {
      assert wind == otherWind;
    }
    windStage = 1;
    for (var i = windCount; i > 0; --i) {
      windIter = i;
      try {
        return (R) handler.run(this, vI, vJ, vF, vD, v);
      } catch (Wind otherWind) {
        if (wind != otherWind)
          throw otherWind;
      }
    }
    windStage = 2;
    return (R) handler.run(this, vI, vJ, vF, vD, v);
  }

  /** 
   * This calls {@link #wind(Wind)} making the top function (e.g. {@link Unwind#brk(Unwind)}) to return <code>val</code>.
   * 
   * @param <R> result type
   * @param <T> argument type
   * @param val value the suspended expression will return on resume
   * @return this call frame return value
   * @throws CThrowable if suspended again
   */
  public <R, T> R resume(final T val) throws CThrowable {
    return wind(Wind.createReturn(val));
  }

  /** 
   * like {@link #wind(Wind)} but isn't supposed to be suspended again, 
   * and converts each {@link CThrowable} into <code>RuntimeException</code> 
   * 
   * @param <R> type of the resulting value
   * @param token an exception object used to wind the stack back
   * @return this call frame return value
   */
  public <R> R windTop(final Wind token) {
    try {
      return wind(token);
    } catch (CThrowable e) {
      throw new RuntimeException("shouldn't be suspended", token);
    }
  }

  /** 
   * like {@link #resume(Object)} but isn't supposed to be suspended after 
   * 
   * @param <T> argument type
   * @param <R> resulting type
   * @param val value the suspended expression will return on resume
   * @return this call frame return value
   */
  public <R, T> R resumeTop(final T val) {
    return windTop(Wind.createReturn(val));
  }

  /** 
   * like {@link #resumeThrow(Throwable)} but isn't supposed to be suspended after 
   * 
   * @param <R> resulting type
   * @param e an exception to throw at the suspended point
   * @return this call frame return value
   */
  public <R> R resumeThrowTop(final Throwable e) {
    return windTop(Wind.createThrow(e));
  }

  /** 
   * This just calls {@link #wind(Wind)} making the top function (e.g. {@link Unwind#brk(Unwind)}) 
   * to throw an exception <code>e</code>.
   * 
   * @param <R> resulting type
   * @param e an exception to throw at the suspended point
   * @return this call frame return value
   * @throws CThrowable if suspended again
   */
  public <R> R resumeThrow(final Throwable e) throws CThrowable {
    return wind(Wind.createThrow(e));
  }

  /** 
   * This is called from the generated code on unwinding
   * 
   * @param e the exception object
   * @param state a pointer into a code where it was suspended
   */
  public void _unwind(final Unwind e, final int state) {
    this.state = state;
    this.next = e.head;
    e.head = this;
  }

  /**
   * This resumes a suspended execution starting from the current frame and 
   * all other frames it called when suspended. It adds this frame 
   * (and all frames it called) on top of the current frame until there
   * are no more frames. And after this it either throws an exception 
   * or returns value specified in <code>wind</code>.
   * 
   * @param <R> resulting value
   * @param wind an exception object for <code>catch</code> handlers
   * @return resulting value of the frame this winds
   * @throws CThrowable if wind is suspended again 
   */
  public <R> R wind(final Wind wind) throws CThrowable {
    return copy().windSelf(wind);
  }

  /**
   * Shallow frame copy
   * 
   * @return a shallow clone of this frame
   */
  public CallFrame copy() {
    try {
      return (CallFrame) clone();
    } catch (CloneNotSupportedException e) {
      throw new RuntimeException("couldn't clone a call frame", e);
    }
  }

  /** 
   * This is called from the generated code to get result of this call
   * 
   * @return the return value of this call
   * @throws Throwable if the call throws an exception
   */
  public Object _refResult() throws Throwable {
    if (windStage < 2)
      throw token;
    if (next == null)
      return token.result();
    try {
      final var ret = next.wind(token);
      return ret;
    } catch (Unwind e) {
      this.next = e.head;
      e.head = this;
      throw e;
    }
  }

  @Override
  protected Object clone() throws CloneNotSupportedException {
    final var ret = (CallFrame) super.clone();
    if (vI != null)
      ret.vI = vI.clone();
    if (vJ != null)
      ret.vJ = vJ.clone();
    if (vF != null)
      ret.vF = vF.clone();
    if (vD != null)
      ret.vD = vD.clone();
    if (v != null)
      ret.v = v.clone();
    return ret;
  }

  /**
   * called from the generated code, this doesn't let finally blocks
   * and too generic exception handlers to be invoked on {@link CThrowable} exceptions
   * 
   * @param e the exception to skip if needed
   * @throws Throwable if the <code>catch</code> handler must be skipped
   */
  public void _skipException(final Throwable e) throws Throwable {
    if (e instanceof CThrowable)
      throw e;
  }

  /**
   * called from the generated code, this doesn't let {@link CThrowable}
   * catch blocks to be executed on {@link Wind} exception
   * 
   * @param e the exception to skip if needed
   * @throws Throwable if the <code>catch</code> handler must be skipped
   */
  public void _skipWind(final Throwable e) throws Throwable {
    if (e instanceof Wind)
      throw e;
  }

  /**
   * This function is called from the generated code, running {@link Wind} handlers in reverse order
   * 
   * @param e the exception to check
   * @throws Throwable if the exception must be ignored
   */
  public void _checkException(final Throwable e) throws Throwable {
    if (e == token) {
      if (windStage == 0) {
        ++windCount;
        throw e;
      } else if (windStage == 1) {
        --windIter;
        if (windIter == 0)
          return;
        throw e;
      }
    }
  }

  /** 
   * This function is called from generated code to get <code>byte</code> result 
   * 
   * @return <code>byte</code> resulting value of this call
   * @throws Throwable if the call throws an exception
   */
  public byte _byteResult() throws Throwable {
    return (byte) _refResult();
  }

  /** 
   * This function is called from generated code to get <code>short</code> result 
   * 
   * @return <code>short</code> resulting value of this call
   * @throws Throwable if the call throws an exception
   */
  public short _shortResult() throws Throwable {
    return (short) _refResult();
  }

  /** 
   * This function is called from generated code to get <code>int</code> result 
   * 
   * @return <code>int</code> resulting value of this call
   * @throws Throwable if the call throws an exception
   */
  public int _intResult() throws Throwable {
    return (int) _refResult();
  }

  /** 
   * This function is called from generated code to get <code>long</code> result 
   * 
   * @return <code>long</code> resulting value of this call
   * @throws Throwable if the call throws an exception
   */
  public long _longResult() throws Throwable {
    return (long) _refResult();
  }

  /** 
   * This function is called from generated code to get <code>float</code> result 
   * 
   * @return <code>float</code> resulting value of this call
   * @throws Throwable if the call throws an exception
   */
  public float _floatResult() throws Throwable {
    return (float) _refResult();
  }

  /** 
   * This function is called from generated code to get <code>double</code> result 
   * 
   * @return <code>double</code> resulting value of this call
   * @throws Throwable if the call throws an exception
   */
  public double _doubleResult() throws Throwable {
    return (double) _refResult();
  }

  /** 
   * This function is called from generated code to get <code>boolean</code> result 
   * 
   * @return <code>boolean</code> resulting value of this call
   * @throws Throwable if the call throws an exception
   */
  public boolean _booleanResult() throws Throwable {
    return (boolean) _refResult();
  }

  /** 
   * This function is called from generated code to get <code>char</code> result 
   * 
   * @return <code>char</code> resulting value of this call
   * @throws Throwable if the call throws an exception
   */
  public char _charResult() throws Throwable {
    return (char) _refResult();
  }

  /** 
   * This function is called from generated code for functions without return values 
   * 
   * @throws Throwable if the call throws an exception
   */
  public void _voidResult() throws Throwable {
    _refResult();
  }
}
