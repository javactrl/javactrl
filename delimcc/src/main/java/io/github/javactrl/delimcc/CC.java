package io.github.javactrl.delimcc;

import io.github.javactrl.ext.CFunction;
import io.github.javactrl.ext.CSupplier;
import io.github.javactrl.rt.CThrowable;
import io.github.javactrl.rt.CallFrame;
import io.github.javactrl.rt.Ctrl;
import io.github.javactrl.rt.Unwind;

/**  */
@Ctrl
public class CC {

  /** 
   * Continuation's scope delimiter 
   * 
   * @param <A> result type
   */
  public static class Prompt<A> {
    /** name of the prompt */
    public final String name;

    /** 
     * Constructor with an optional name 
     * 
     * @param name optional name
     */
    public Prompt(final String name) {
      this.name = name;
    }

    /** prompt's counter for unique name generation */
    private static int count = 0;

    /** Constructor with generated name */
    public Prompt() {
      this(String.format("p%d",count++));
    }
  }

  /**
   * a sub-continuation expecting values of type <code>A</code> and producing values of type
   * <code>B</code>
   * 
   * @param <A> argument type
   * @param <B> result type
   */
  public static class SubCont<A, B> {
    final CallFrame frame;

    /** 
     * Constructor
     * 
     * @param fame unwound frame for resuming
     */
    SubCont(final CallFrame frame) {
      this.frame = frame;
    }
  }

  /** 
   * Unwind token for the delimited continuation
   */
  static class CCUnwind extends Unwind {
    /** Prompt till to unwind  */
    final Prompt<?> prompt;
    /** Function to execute on the delimiting <code>catch</code> */
    final CFunction<? extends SubCont<?, ?>, ?> body;

    /** 
     * Constructor
     * 
     * @param <A> argument type
     * @param <B> result type
     * @param prompt delimiting prompt
     * @param body function called on the delimiting <code>catch</code>
     */
    <A, B> CCUnwind(final Prompt<B> prompt, final CFunction<SubCont<A, B>, B> body) {
      super();
      this.prompt = prompt;
      this.body = body;
    }
  }

  /**
   * This uses prompt in its first operand to delimit the current continuation during
   * the evaluation of its second operand.
   * 
   * @param <A> result type
   * @param prompt prompt object
   * @param block the body where the continuation is delimited
   * @return the result of the block
   * @throws CThrowable if itself captured
   */
  @SuppressWarnings("unchecked")
  public static <A> A pushPrompt(final Prompt<A> prompt, final CSupplier<A> block) throws CThrowable {
    try {
      return block.get();
    } catch (final CCUnwind u) {
      if (u.prompt != prompt)
        throw u;
      final var frame = u.head.next;
      final var body = (CFunction<SubCont<Object, A>, A>) u.body;
      return body.apply(new SubCont<Object, A>(frame));
    }
  }

  /**
   * Captures a portion of the current continuation back to
   * but not including the activation of pushPrompt with prompt <code>prompt</code>, 
   * aborts the current continuation back to and including the activation of 
   * {@link #pushPrompt(Prompt, CSupplier)}, and invokes <code>handler</code>, 
   * passing it an abstract value representing the captured subcontinuation.
   * 
   * If more than one activation of pushPrompt with prompt p is still active,
   * the most recent enclosing activation, i.e., the one that delimits the
   * smallest subcontinuation, is selected.
   * 
   * @param <A> argument type
   * @param <B> result type
   * @param prompt delimiting prompt
   * @param handler function receiving sub-continuation representation
   * @return result value
   * @throws CThrowable if itself captured
   */
  public static <A, B> A withSubCont(final Prompt<B> prompt, final CFunction<SubCont<A, B>, B> handler)
      throws CThrowable {
    final CSupplier<A> supplier = Unwind.brk(new CCUnwind(prompt, handler));
    return supplier.get();
  }

  /**
   * This composes sub-continuation <code>subk</code> with current continuation and 
   * evaluates its second argument
   * 
   * @param <A> argument type
   * @param <B> result type
   * @param subk some sub-continuation captured before
   * @param a function to execute just after the sub-continuation is resumed 
   * @return result value
   * @throws CThrowable if itself captured
   */
  public static <A, B> B pushSubCont(final SubCont<A, B> subk, final CSupplier<A> a) throws CThrowable {
    final B res = subk.frame.resume(a);
    return res;
  }

  /**
   * creates new prompt, and applies <code>body</code> passing this new prompt,
   * delimiting resulting continuation with it
   * 
   * @param <A> result type
   * @param body function called on the delimiting <code>catch</code>
   * @return the body's resulting value
   * @throws CThrowable if itself captured
   */
  public static <A> A reset(final CFunction<Prompt<A>, A> body) throws CThrowable {
    final var p = new Prompt<A>();
    return pushPrompt(p, () -> body.apply(p));
  }

  /**
   * caputes and aborts the current continuation until <code>prompt</code> 
   * and applies <code>body</code> passing captured continuation as a function 
   * to its argument, delimits captured and resulting continuations
   * 
   * @param <A> argument type
   * @param <B> result type
   * @param prompt delimiting prompt
   * @param body function receiving the captured sub-continuation
   * @return an argument passed to the sub-continuation
   * @throws CThrowable if itself captured
   */
  public static <A, B> A shift(final Prompt<B> prompt, final CFunction<CFunction<CSupplier<A>, B>, B> body) throws CThrowable {
    return withSubCont(prompt,
        sk -> pushPrompt(prompt, () -> body.apply(a -> pushPrompt(prompt, () -> pushSubCont(sk, a)))));
  }

  /** 
   * aborts current continution up to the <code>prompt</code>
   * 
   * @param <A> argument type
   * @param <B> result type
   * @param prompt delimiting prompt
   * @param body function called after the contunuation is aborted
   * @return an argument passed to the sub-continuation
   * @throws CThrowable if itself captured
   */
  public static <A, B> A abort(final Prompt<B> prompt, final CSupplier<B> body) throws CThrowable {
    return withSubCont(prompt, sk -> body.get());
  }

  /**
   * caputes and aborts the current continuation until <code>prompt</code> 
   * and applies <code>body</code> passing captured continuation as a function 
   * to its argument, doesn't delimit captured continuation but delimits 
   * resultinging continuation
   * 
   * @param <A> argument type
   * @param <B> result type
   * @param prompt delimiting prompt
   * @param body function receiving the captured sub-continuation
   * @return an argument passed to the sub-continuation
   * @throws CThrowable if itself captured
   */
  public static <A, B> A control(final Prompt<B> prompt, final CFunction<CFunction<CSupplier<A>, B>, B> body) throws CThrowable {
    return withSubCont(prompt, sk -> pushPrompt(prompt, () -> body.apply(a -> pushSubCont(sk, a))));
  }

  /**
   * This caputes and aborts the current continuation until <code>prompt</code> 
   * and applies <code>body</code>
   * passing captured continuation as a function to its argument,
   * delimits captured, doesn't delimit resultinging continuation
   *
   * @param <A> argument type
   * @param <B> result type
   * @param prompt delimiting prompt
   * @param body function receiving the captured sub-continuation
   * @return an argument passed to the sub-continuation
   * @throws CThrowable if itself captured
   */
  public static <A, B> A shift0(final Prompt<B> prompt, final CFunction<CFunction<CSupplier<A>, B>, B> body) throws CThrowable {
    return withSubCont(prompt, sk -> body.apply(a -> pushPrompt(prompt, () -> pushSubCont(sk, a))));
  }

  /**
   * caputes and aborts the current continuation until <code>prompt</code> 
   * and applies <code>body</code> passing captured continuation as 
   * a function to its argument, doesn't delimit captured and resulting continuations
   *
   * @param <A> argument type
   * @param <B> result type
   * @param prompt delimiting prompt
   * @param body function receiving the captured sub-continuation
   * @return an argument passed to the sub-continuation
   * @throws CThrowable if itself captured
   */
  public static <A, B> A control0(final Prompt<B> prompt, final CFunction<CFunction<CSupplier<A>, B>, B> body) throws CThrowable {
    return withSubCont(prompt, sk -> body.apply(a -> pushSubCont(sk, a)));
  }

}
