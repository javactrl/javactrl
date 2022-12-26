# JVM continuations

![CI](https://github.com/javactrl/javactrl/actions/workflows/main.yml/badge.svg)

This library is yet another implementation of delimited continuations for JVM using bytecode instrumentation. It primarily implements resumable exceptions. And there is also a classical multi-prompt delimited continuations implementation based on the resumable exceptions.

Use plain Java exception handling to capture, compose and run parts of programs' call stack. No functional programming knowledge is required.

Unlike the common perception, continuations are easy and incredibly helpful for achieving a cleaner code that is much simpler to read, write and maintain. For sure, it's easier to produce a more obscure code with continuations, but we can have the same horrible code without them too. However, continuations provide opportunities to make everything better in an easy way. This is already proven in many programming languages with coroutines (or single-shot continuations) used for asynchronous functions or generators.

But continuations can give even more if extended a bit. For example, the continuations can simplify microservices orchestration logic in enterprise event-based applications. These are usually many small event handlers with vague and unintended dependencies, fragile and hard to reason and extend. However, with continuations, the same orchestrator can be a simple direct style code, mapping domain logic to code one-to-one, simple to write, test, maintain, and modify.

This library extends Java exceptions. There are two new special exception classes (namely `Wind` and `Unwind`). 

Exceptions with the `Unwind` type (or its descendants) work the same as any checked Java exception, except it's possible to continue executing the code after their `throw`. And it's even possible to do this more than once. 

So here is a trivial example:

```java
@Ctrl
class Example1 {
  static int giveMe5() throws CThrowable {
    int count = 0;
    try {
      return (Integer)Unwind.brk(new Unwind(0)) + (Integer)Unwind.brk(new Unwind(2));
    } catch (Unwind u) {
      count++;
      return u.head.resume((Integer)u.payload + count);
    }
  }
}
```

The `@Ctrl` annotation enables every function in the class (including lamdas) for instrumentation. In the this way marked classes, every function with  `CThrowable` in its exception specification is transformed to support delimited continuations. The `CThrowable` class is a parent for both `Unwind` and `Wind`. And the `head` field of the `Unwind` object stores the last call frame of the captured call stack. If it's `null` it means the code wasn't instrumented.

Since `throw` is a statement in Java and doesn't assume it may return anything, there is a helper function `Unwind::brk`, which contains just a single `throw`. Its result is the value we passed as an argument for `resume`. The argument of the `Unwind` constructor is just assigned to its `payload` field. Users may want to implement a similar function to extend its checked exceptions list. 

The resulting value of `resume` is the current function call result. The library shallowly clones the captured call frames before resuming and adds them on top of the current frame. So this `resume` call is like calling the function itself again, but the execution starts right after the corresponding `throw` statement (`brk` call). So in this example, it will look like `giveMe5` function called itself twice.

When the suspended frames are resumed, the library throws an exception with the type `Wind`. It's mostly needed for resource management, but some program logic also can be organized using it. For example, when we suspend and unwind a part of a stack, we don't know if it is ever resumed. Moreover, the whole execution stack could be saved into a file or a DB and resumed on another computer months later, or it may never be executed.

Usually, `finally` blocks are used for cleaning up resources in Java programms, but the same block can be resumed more than once while everything in `finally` is supposed to be cleaned entirely.

To implement proper resource handling with continuations, we need to know when we suspend and resume. And for this, we implement `catch` handlers for `Wind` and `Unwind`. 

```java
      var a = allocateResource();
      try {
        // ..........
      } catch(Wind w) {
        a.unpark();
        throw w;
      } catch(Unwind u) {
        a.park();
        throw u;
      } finally {
        a.destroy();
      }
```

For this to work correctly, both `Wind` and `Unwind` (and their descendants) don't trigger `finally` blocks. Also, `catch` blocks with the `Throwable` type don't catch them.

The `Wind` exceptions are even more special - they propagate in the reverse direction - from caller to callee and outer blocks to nested. This propagation order is needed for proper resource dependencies management. If one resource depends on another, we need to `allocate`/`park`/`unpark` them in the appropriate order:

```java
  var a = allocateResourceA();
  try {
	    var b = allocateResourceB(a);
      // ....
	    try {
        // ....
	    } catch(Wind w) {
        b.unparkB(a);
        throw w;
      }
  } catch(Wind w) {
    a.unparkA();
    throw w;
  }
```

In this example, `b.unparkB(a)` is executed after `a.unparkA()`, so its dependency is already prepared.

This is enough to implement all the delimited continuations operators, monads, and many other helpful language extensions.

## Why not Project Loom (or others)

Unlike most other implementations, this one supports multishot and serializable continuations. But the more considerable difference is using exception handlers instead of callbacks, so-called resumable exceptions. It's easy to convert the exceptions-based continuations implementation into a callbacks-based one, but not vice versa. Here is an implementation of all classical combinators from [A Monadic Framework for Delimited Continuations](https://cs.indiana.edu/~dyb/pubs/monadicDC.pdf) paper in a couple of lines of code each in a separate subproject [delimcc](delimcc).

There are a few benefits of exceptions. Java requires captured variables to be effectively final, so we cannot change local variables from the callbacks handler body. But it's possible to do this from an exception handler. It's not a hack of Java's safety restrictions since it makes a shallow clone of each call frame. Also, code with handlers is usually less verbose. It's also easier to write generic code with checked exceptions.

However, in Java, exceptions cannot have generic arguments. It means the payload values we transmit between `throw` and `catch` and back can have only some abstract types. So we'll need to resort to unchecked casts sometimes. With the callbacks, we can use the generics to the extent the Java type system offers.

Most of the JVM continuation libraries available are mostly for async programming. The other usages are usually considered too academic or crazy. Here's, for example, a quote from Project Loom introduction article [Why Continuations are Coming to Java](https://www.infoq.com/presentations/continuations-java/):

> You can do some crazy stuff with this, you could write programs that actually go back in time, and most people have no need for and I'm not sure we're actually going to implement that, but we could.

With this library, I want to show the multi-shot continuations usage isn't crazy. Moreover, it's possible to add helpful language features from other mainstream programming languages from which many developers can benefit (see examples below). I hope Project Loom team will change their mind and this library won't be needed.

## Usage

### Java agent

Library usages requires bytecode instrumentation. The easiest way is to pass its ".jar" file as `-javaagent` option to JVM.

```
  -javaagent:path-to-jvactrl-core-jar.jar 
```

TODO: maven/gradle

## AOT instrumentation

It's possible to instrument ahead of time. For this run, execute the .jar file passing as its argument paths for input and output .class files.

There are currently no build system plugins, but, for example, in gradle [JavaExec](https://docs.gradle.org/current/dsl/org.gradle.api.tasks.JavaExec.html) can be used or [Java](https://ant.apache.org/manual/Tasks/java.html) ant task.

## Examples

It's well known we can represent any monad with delimited continuations. There is a paper about this - [Representing Monad](https://dl.acm.org/doi/10.1145/174675.178047). The paper is hard to read without functional programming experience. But with javactrl, you don't even need to know what Monad is to get all the benefits.

The Java type system doesn't let us define usable abstract Monad, but it's pretty simple to make a representation for concrete ones. So let's first represent `java.util.stream.Stream`. The code from the paper can be translated into javactrl this way:

```java
  public static <T> T all(Stream<T> t) throws CThrowable { 
    throw new Unwind(t);
  }
 
  public static <T> Stream<T> run(final CSupplier<T> s) throws CThrowable {
    try {
      return Stream.of(s.get());
    } catch (final Unwind e) {
      return ((Stream<?>) e.payload).flatMap(v -> e.head.resumeTop(v));
    }
  }
```

Here `CSupplier` is just a functional interface like `java.util.function.Supplier` but with `throws CThrowable`. The `resumeTop` method is like `resume` except it handles `CThrowable`. This is it - we've just developed a stream comprehension like in [Python](https://docs.python.org/3/tutorial/datastructures.html#list-comprehensions) and many other programming languages. But our stream comprehensions don't require syntax extensions and work with any Java construct (function calls, loops, exception handling, branches, and so on). 

Here is a usage example returning a stream `[6,6,8,8]`:

```java
    run(() -> {
      int x = all(Stream.of(1, 2, 3));
      int y = all(Stream.of(4, 5, 6));
      int ret = x + y;
      if (ret % 2 == 1)
        all(Stream.empty());
      return ret;
    })
```

We can go even further and implement the same for more advanced streams. Say, for example, Observable from RxJava. There are more ways to compose. Namely, we can use `flatMap`, `switchMap`, or `concatMap` depending on how exactly we want to compose the streams. So we make a subclass of `Unwind` for each operation and a wrapper like this:

```java
  public static <T> Observable<T> run(final CSupplier<T> s) throws CThrowable {
    try {
      return Observable.just(s.get());
    } catch (final FlatUnwind e) {
      return e.value.flatMap(e.head::resumeTop);
    } catch (final SwitchUnwind e) {
      return e.value.switchMap(e.head::resumeTop);
    } catch (final ConcatUnwind e) {
      return e.value.concatMap(e.head::resumeTop);
    }
  }
```

Here `FlatMap` class is just:

```java
  public static class FlatUnwind extends Unwind {
    final Observable<?> value;

    FlatUnwind(final Observable<?> value) {
      this.value = value;
    }
  }

  public static <T> T flat(Observable<T> v) throws FlatUnwind {
    throw new FlatUnwind(v);
  }

```

And in the usage code, we can use an expression like `flat(myObservable)` to get elements of `myObservable` when they arrive. 

This way, we've turned plain Java into a fully featured reactive programming language. In the same way, we can get logical, probabilistic, adaptive (incremental computation), and parallel programming languages as straightforward as in these examples. 

For completeness, there is a runner for async code using `java.util.concurrent.CompletableFuture`:
 
```java
  class AsyncUnwind extends Unwind {
  // ...
  };
  // ..
  public <T> T await(CompletableFuture<T> v) throws CThrowable {
     throw new AsyncUnwind(v);
  }
  // ..
  public <T> CompletableFuture<T> run(CSupplier<T> handler) throws CThrowable {
    try {
      return CompletableFuture.completedFuture(handler.get());
    } catch (SyncUnwind su) {
      return su.value.thenCompose(su.head::resumeTop);
    } catch (AsyncUnwind au) {
      return au.value.thenComposeAsync(au.head::resumeTop, au.executor);
    }
  }
```

There `SyncUnwind` and `AsyncUnwind` are the same simple subclasses of `Unwind`. As you can see, we can continue the execution either synchronously or asynchronously. This is unlike, for example, JavaScript async/await, where we can only continue asynchronously after the Promise is resolved there. 

It's well known how the switching from async operations callbacks to direct style async code (for example, async/await in JavaScript) helps to clean up the code. Even better results can be achieved by using direct style code in event-based applications where using small callbacks is common. This is implemented in another library for creating Apache Kafka-based workflow definitions.

## Caveats

Unfortunately, some information required to do the transformation properly is lost when the Java code is compiled from source to bytecode. And the java compiler, of course, has no idea the code it compiles can be executed more than once. Fortunately, the required information can be recovered using debugging information. Usually, the debugging information is available by default, but some tools may remove it. If it's removed before the instrumentation, the exception handlers may not work. However, if you use callback handlers, it should work anyway.

Suspending in constructors isn't supported. This also includes suspending anonymous classes fields initialization.

## Debugging

The usual Java debugger should still work after instrumentation. It may behave weirdly on steppings, but breakpoints and variable values views work well most of the time. 

There are, however, even more debugging opportunities if the state is serializable. It can be stored somewhere and loaded just for time-traveling debugging.
