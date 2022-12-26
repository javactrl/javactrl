package io.github.javactrl.ext;

import static io.github.javactrl.rt.Unwind.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.PrintStream;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;

import io.github.javactrl.rt.CThrowable;
import io.github.javactrl.rt.Ctrl;
import io.github.javactrl.rt.Unwind;
import io.github.javactrl.rt.Wind;
import io.github.javactrl.test.kit.Snapshot;
import static java.lang.String.format;

@Ctrl
public class ConcurrencyTest {

  @Snapshot
  PrintStream out;

  static final int NUMBER_OF_THREADS = 10;
  static final int MAX_DELAY = 20;
  static final int NUMBER_OF_REPEATS = 5;

  final ScheduledThreadPoolExecutor threadPool = new ScheduledThreadPoolExecutor(NUMBER_OF_THREADS);

  int withDelay(final int v, final int timeout) throws CThrowable {
    return brk(new Unwind() {
      @Override
      public void boundary() {
        threadPool.schedule(() -> CRunnable.brackets(() -> {
          this.head.resume(v);
        }), timeout, TimeUnit.MILLISECONDS);
      }
    });
  }

  int withDelay(final int v) throws CThrowable {
    return withDelay(v, (int) (Math.random() * MAX_DELAY));
  }

  static <T> Future<T> run(CSupplier<T> sup) {
    final var lock = new CompletableFuture<T>();
    CRunnable.brackets(() -> {
      try {
        final var r = sup.get();
        lock.complete(r);
      } catch (Throwable t) {
        lock.completeExceptionally(t);
      }
    });
    return lock;
  }

  @Test
  void simpleAsyncBlocks() throws InterruptedException, ExecutionException {
    for (var i = 0; i < NUMBER_OF_REPEATS; ++i) {
      final Future<Integer> lock = run(() -> {
        out.println("block-started");
        final var x = withDelay(2);
        assertEquals(2, x);
        final var stackSize1 = Thread.currentThread().getStackTrace().length;
        out.println(format("in-handler1: x=%d",x));
        final var y = withDelay(10);
        assertEquals(10, y);
        assertEquals(stackSize1, Thread.currentThread().getStackTrace().length);
        out.println(format("in-handler2: x=%d, y=%d",x, y));
        return x * y;
      });
      final int ret = lock.get();
      assertEquals(20, ret);
      out.println(format("future-returned: ret=%s",ret));
    }
  }

  @Test
  void coopAllNoSuspension() {
    CRunnable.brackets(() -> {
      final var r = Concurrency.allOf(() -> 10, () -> 20, () -> 30).toArray(Integer[]::new);
      assertArrayEquals(new Integer[] { 10, 20, 30 }, r);
    });
    out.println("# all threads are exited immediately, one of them thrown an exception");
    CRunnable.brackets(() -> {
      try {
        Concurrency.allOf(() -> 10, () -> {
          throw new RuntimeException("test1");
        }, () -> 30).toArray(Integer[]::new);
      } catch (RuntimeException t) {
        assertEquals("test1", t.getMessage());
        return;
      }
      throw new AssertionFailedError("should exit with an exception");
    });
    out.println("# all threads are exited immediately, two of them thrown an exception");
    CRunnable.brackets(() -> {
      try {
        Concurrency.allOf(() -> 10, () -> {
          throw new RuntimeException("test1");
        }, () -> {
          out.println("shouldn't be executed");
          throw new RuntimeException("test1");
        }).toArray(Integer[]::new);
      } catch (RuntimeException t) {
        assertEquals("test1", t.getMessage());
        return;
      }
      throw new AssertionFailedError("should exit with an exception");
    });
  }

  @Test
  void coopAllSimpleSuspension() {
    out.println("# one suspended ");
    {
      final var x = new Unwind();
      CRunnable.brackets(() -> {
        final var r = Concurrency.allOf(() -> 10, () -> 20 + (int) brk(x), () -> 30)
            .toArray(Integer[]::new);
        assertArrayEquals(new Integer[] { 10, 22, 30 }, r);
      });
      x.head.resumeTop(2);
    }
    out.println("# all threads are suspended once");
    {
      final var x = new Unwind();
      final var y = new Unwind();
      final var z = new Unwind();
      CRunnable.brackets(() -> {
        final var r = Concurrency
            .allOf(() -> 10 + (int) brk(x), () -> 20 + (int) brk(y), () -> 30 + (int) brk(z))
            .toArray(Integer[]::new);
        assertArrayEquals(new Integer[] { 11, 22, 33 }, r);
      });
      x.head.resumeTop(1);
      y.head.resumeTop(2);
      z.head.resumeTop(3);
    }
    out.println("# all threads are suspended once, but in reverse order");
    {
      final var x = new Unwind();
      final var y = new Unwind();
      final var z = new Unwind();
      CRunnable.brackets(() -> {
        final var r = Concurrency
            .allOf(() -> 10 + (int) brk(x), () -> 20 + (int) brk(y), () -> 30 + (int) brk(z))
            .toArray(Integer[]::new);
        assertArrayEquals(new Integer[] { 11, 22, 33 }, r);
      });
      z.head.resumeTop(3);
      y.head.resumeTop(2);
      x.head.resumeTop(1);
    }
    out.println("# different number of supensions");
    {
      final var x = new Unwind();
      final var y1 = new Unwind();
      final var y2 = new Unwind();
      CRunnable.brackets(() -> {
        final var r = Concurrency
            .allOf(() -> 10, () -> 20 + (int) brk(y1) + (int) brk(y2), () -> 30 + (int) brk(x))
            .toArray(Integer[]::new);
        assertArrayEquals(new Integer[] { 10, 26, 31 }, r);
      });
      y1.head.resumeTop(2);
      y2.head.resumeTop(4);
      x.head.resumeTop(1);
    }
  }

  @Test
  void coopAllExceptionAfterSuspension() {
    out.println("# one of the threads thrown an exception after suspension");
    out.println("## others exited");
    {
      final var x = new Unwind();
      CRunnable.brackets(() -> {
        try {
          Concurrency.allOf(() -> 10, () -> {
            throw new RuntimeException(format("test%d",(int)brk(x)));
          }, () -> 30).toArray(Integer[]::new);
        } catch (RuntimeException t) {
          out.println(format("thrown %s",t.getMessage()));
          assertEquals("test1", t.getMessage());
          return;
        }
        throw new AssertionFailedError("should exit with an exception");
      });
      x.head.resumeTop(1);
    }
    out.println("## throw resumed");
    {
      final var x = new Unwind();
      CRunnable.brackets(() -> {
        try {
          Concurrency.allOf(() -> 10, () -> {
            return brk(x);
          }, () -> 30).toArray(Integer[]::new);
        } catch (RuntimeException t) {
          out.println(format("thrown %s",t.getMessage()));
          assertEquals("test1", t.getMessage());
          return;
        }
        throw new AssertionFailedError("should exit with an exception");
      });
      x.head.resumeThrowTop(new RuntimeException("test1"));
    }
    out.println("## there are other suspnded threads");
    {
      final var x = new Unwind();
      final var y = new Unwind();
      CRunnable.brackets(() -> {
        try {
          Concurrency.allOf(() -> brk(x), () -> {
            throw new RuntimeException(format("test%d",(int)brk(y)));
          }, () -> 30).toArray(Integer[]::new);
        } catch (RuntimeException t) {
          out.println(format("thrown %s",t.getMessage()));
          assertEquals("test2", t.getMessage());
          return;
        }
        throw new AssertionFailedError("should exit with an exception");
      });
      x.head.resumeTop(1);
      y.head.resumeTop(2);
    }
    out.println("## one of the other threads was resumed");
    {
      final var x = new Unwind();
      final var y = new Unwind();
      final var z = new Unwind();
      CRunnable.brackets(() -> {
        try {
          Concurrency.allOf(() -> brk(x), () -> {
            final int t = brk(y);
            throw new RuntimeException(format("test%d",t));
          }, () -> brk(z)).toArray(Integer[]::new);
        } catch (RuntimeException t) {
          out.println(format("thrown %s",t.getMessage()));
          assertEquals("test2", t.getMessage());
          return;
        }
        throw new AssertionFailedError("should exit with an exception");
      });
      y.head.resumeTop(2);
      x.head.resumeTop(1);
    }
    out.println("## another thread thrown earlier");
    {
      final var x = new Unwind();
      final var y = new Unwind();
      CRunnable.brackets(() -> {
        try {
          Concurrency.allOf(() -> brk(x), () -> {
            final int t = brk(y);
            throw new RuntimeException(format("test%d",t));
          }, () -> {
            throw new RuntimeException("testz");
          }).toArray(Integer[]::new);
        } catch (RuntimeException t) {
          out.println(format("thrown %s",t.getMessage()));
          assertEquals("testz", t.getMessage());
          return;
        }
        throw new AssertionFailedError("should exit with an exception");
      });
    }
  }

  @Test
  void coopAllThrowWithCancelation() {
    out.println("# cancelation token is thrown");
    {
      final var x = new Unwind();
      final var y = new Unwind();
      final var z = new Unwind();
      CRunnable.brackets(() -> {
        try {
          Concurrency.allOf(() -> brk(x), () -> {
            final int t = brk(y);
            throw new RuntimeException(format("test%d",t));
          }, () -> {
            try {
              int t = brk(z);
              return t;
            } catch (CancellationException e) {
              out.println("cancelled");
              throw e;
            }
          }).toArray(Integer[]::new);
        } catch (RuntimeException t) {
          out.println(format("thrown %s - once!",t.getMessage()));
          assertEquals("test2", t.getMessage());
          return;
        }
        throw new AssertionFailedError("should exit with an exception");
      });
      y.head.resumeTop(2);
      out.println("done!");
    }
    out.println("# suspend after cancel");
    {
      final var x = new Unwind();
      final var y = new Unwind();
      final var z1 = new Unwind();
      final var z2 = new Unwind();
      CRunnable.brackets(() -> {
        try {
          Concurrency.allOf(
              () -> {
                try {
                  int t1 = brk(z1);
                  return t1;
                } catch (CancellationException e) {
                  out.println("cancelled");
                }
                int t2 = brk(z2);
                out.println(format("z2-resumed = %d",t2));
                return t2 * 100;
              },
              () -> brk(x),
              () -> {
                final int t = brk(y);
                throw new RuntimeException(format("test%d",t));
              }).toArray(Integer[]::new);
        } catch (RuntimeException t) {
          out.println(format("catch %s",t.getMessage()));
          assertEquals("test2", t.getMessage());
          return;
        }
        throw new AssertionFailedError("should exit with an exception");
      });
      y.head.resumeTop(2);
      x.head.resumeTop(1);
      out.println("resume z2");
      z2.head.resumeTop(8);
      out.println("done!");
    }
  }

  @Test
  void coopCancellAllWithAllExited() {
    final var x = new Unwind();
    final var y = new Unwind();
    final var z = new Unwind();
    CRunnable.brackets(() -> {
      try {
        Concurrency.allOf(() -> brk(x), () -> {
          final int t = brk(y);
          throw new RuntimeException(format("test%d",t));
        }, () -> {
          try {
            int t = brk(z);
            return t;
          } catch (CancellationException e) {
            out.println("cancelled");
            throw e;
          }
        }).toArray(Integer[]::new);
      } catch (final Unwind u) {
        try {
          u.head.resumeThrow(new RuntimeException("cancel1"));
        } catch (final RuntimeException t) {
          out.println(format("catchHandler %s - once!",t.getMessage()));
          assertEquals("cancel1", t.getMessage());
        }
        return;
      } catch (final RuntimeException t) {
        out.println(format("thrown %s - once!",t.getMessage()));
        assertEquals("cancel1", t.getMessage());
        throw t;
      }
      throw new AssertionFailedError("should exit with an exception");
    });
    y.head.resumeTop(2);
    out.println("done!");
  }

  @Test
  void coopCancellAllWithRemaining() {
    final var x = new Unwind();
    final var y = new Unwind();
    final var z = new Unwind();
    final var xc = new Unwind();
    final var yc = new Unwind();
    final var zc = new Unwind();
    CRunnable.brackets(() -> {
      var unwinding = false;
      try {
        Concurrency.allOf(
            () -> {
              try {
                return brk(x);
              } catch (CancellationException e) {
                out.println("cancelling x...");
                out.println(format("x cancelled %d",(int)brk(xc)));
                return 101;
              }
            }, () -> {
              try {
                return brk(y);
              } catch (CancellationException e) {
                out.println("cancelling y...");
                out.println(format("y cancelled %d",(int)brk(yc)));
                throw new RuntimeException("some other error");
              }
            }, () -> {
              try {
                return brk(z);
              } catch (CancellationException e) {
                out.println("cancelling z...");
                out.println(format("z cancelled %d",(int)brk(zc)));
                return 103;
              }
            }).toArray(Integer[]::new);
      } catch (final Unwind u) {
        if (unwinding) {
          out.println("already unwinding");
          return;
        }
        out.println("first unwind");
        unwinding = true;
        u.head.resumeThrow(new RuntimeException("cancel1"));
        return;
      } catch (final RuntimeException t) {
        out.println(format("thrown %s - once!",t.getMessage()));
        assertEquals("cancel1", t.getMessage());
        return;
      }
      throw new AssertionFailedError("should exit with an exception");
    });
    out.println("resuming in cancel");
    xc.head.resumeTop(11);
    yc.head.resumeTop(21);
    zc.head.resumeTop(31);
    out.println("done!");
  }

  @Test
  void coopAllWindUnwindTest() {
    final var x = new Unwind();
    final var y = new Unwind();
    final var z = new Unwind();
    CRunnable.brackets(() -> {
      try {
        final var r = Concurrency
            .allOf(
                () -> {
                  try {
                    return 10 + (int) brk(x);
                  } catch (Wind w) {
                    out.println("Wind-x called once");
                    throw w;
                  } catch (Unwind u) {
                    out.println("Unwind-x called once");
                    throw u;
                  }
                },
                () -> {
                  try {
                    return 20 + (int) brk(y);
                  } catch (Wind w) {
                    out.println("Wind-y called once");
                    throw w;
                  } catch (Unwind u) {
                    out.println("Unwind-y called once");
                    throw u;
                  }
                },
                () -> {
                  try {
                    return 30 + (int) brk(z);
                  } catch (Wind w) {
                    out.println("Wind-z called once");
                    throw w;
                  } catch (Unwind u) {
                    out.println("Unwind-z called once");
                    throw u;
                  }
                })
            .toArray(Integer[]::new);
        out.println("allOf exited");
        assertArrayEquals(new Integer[] { 11, 22, 33 }, r);
      } catch (Wind w) {
        out.println("allOf Wind - called once");
      } catch (Unwind w) {
        out.println("allOf Unwind - called once");
      }
    });
    out.println("everything sleeps");
    x.head.resumeTop(1);
    y.head.resumeTop(2);
    z.head.resumeTop(3);
    out.println("done!");
  }

  @Test
  void coopAnyNoSuspension() {
    CRunnable.brackets(() -> {
      final var r = Concurrency.anyOf(() -> {
        out.println("anyOf-1");
        return 10;
      }, () -> {
        out.println("anyOf-2 - never executed");
        return 10;
      }, () -> {
        throw new AssertionFailedError("shouldn't executed");
      });
      assertEquals(10, r);
    });
    out.println("# first thread throws");
    CRunnable.brackets(() -> {
      try {
        Concurrency.anyOf(() -> {
          throw new RuntimeException("test1");
        }, () -> 30);
      } catch (RuntimeException t) {
        assertEquals("test1", t.getMessage());
        return;
      }
      throw new AssertionFailedError("should exit with an exception");
    });
    out.println("# more than one throws");
    CRunnable.brackets(() -> {
      try {
        Concurrency.anyOf(() -> {
          throw new RuntimeException("test1");
        }, () -> {
          out.println("shouldn't be executed");
          throw new RuntimeException("test1");
        });
      } catch (RuntimeException t) {
        assertEquals("test1", t.getMessage());
        return;
      }
      throw new AssertionFailedError("should exit with an exception");
    });
  }

  @Test
  void coopAnyOtherSuspended() {
    {
      final var x = new Unwind();
      CRunnable.brackets(() -> {
        final var r = Concurrency.anyOf(
            () -> brk(x),
            () -> {
              out.println("anyOf-2");
              return 11;
            }, () -> {
              throw new AssertionFailedError("shouldn't executed");
            });
        out.println(format("any-returned=%d",r));
        assertEquals(11, r);
      });
    }
    {
      final var x = new Unwind();
      final var y = new Unwind();
      CRunnable.brackets(() -> {
        final var r = Concurrency.anyOf(
            () -> brk(x),
            () -> {
              out.println("anyOf-2");
              final int t = brk(y);
              out.println(format("anyOf-2: t=%d",t));
              return 10 + t;
            });
        out.println(format("any-returned=%d",r));
        assertEquals(11, r);
      });
      y.head.resumeTop(1);
    }
  }

  @Test
  void coopAnyThrowOtherSuspended() {
    {
      final var x = new Unwind();
      CRunnable.brackets(() -> {
        try {
          Concurrency.anyOf(
              () -> brk(x),
              () -> {
                out.println("anyOf-2");
                throw new RuntimeException("test1");
              }, () -> {
                throw new AssertionFailedError("shouldn't executed");
              });
        } catch (RuntimeException e) {
          out.println(format("thrown immediately %s",e.getMessage()));
          assertEquals("test1", e.getMessage());
          return;
        }
        throw new AssertionFailedError("shouldn't be executed");
      });
    }
    {
      final var x = new Unwind();
      final var y = new Unwind();
      CRunnable.brackets(() -> {
        try {
          Concurrency.anyOf(
              () -> brk(x),
              () -> {
                out.println("anyOf-2");
                final int t = brk(y);
                out.println(format("anyOf-2: t=%d",t));
                throw new RuntimeException(format("test%d",t));
              });
        } catch (RuntimeException e) {
          out.println(format("thrown immediately %s",e.getMessage()));
          assertEquals("test2", e.getMessage());
          return;
        }
        throw new AssertionFailedError("shouldn't be executed");
      });
      y.head.resumeTop(2);
    }
  }

  @Test
  void coopAnyOtherCanceled() {
    out.println("# something returned immediately");
    {
      final var x = new Unwind();
      CRunnable.brackets(() -> {
        final var r = Concurrency.anyOf(
            () -> {
              try {
                return brk(x);
              } catch (CancellationException e) {
                out.println("cancelled");
                throw e;
              }
            },
            () -> {
              out.println("anyOf-2");
              return 11;
            }, () -> {
              throw new AssertionFailedError("shouldn't executed");
            });
        out.println(format("any-returned=%d",r));
        assertEquals(11, r);
      });
    }
    out.println("# something returned after resuming");
    {
      final var x = new Unwind();
      final var y = new Unwind();
      CRunnable.brackets(() -> {
        final var r = Concurrency.anyOf(
            () -> {
              try {
                return brk(x);
              } catch (CancellationException e) {
                out.println("cancelled");
                throw e;
              }
            },
            () -> {
              out.println("anyOf-2");
              final int t = brk(y);
              out.println(format("anyOf-2: t=%d",t));
              return 10 + t;
            });
        out.println(format("any-returned=%d",r));
        assertEquals(11, r);
      });
      y.head.resumeTop(1);
    }
    out.println("# something returned immediately, supeended after cancel");
    {
      final var x1 = new Unwind();
      final var x2 = new Unwind();
      CRunnable.brackets(() -> {
        final var r = Concurrency.anyOf(
            () -> {
              try {
                out.println("anyOf-1");
                return brk(x1);
              } catch (CancellationException e) {
                out.println("cancelled");
              }
              final int t = brk(x2);
              out.println(format("t recv: %d",t));
              assertEquals(2, t);
              return t;
            },
            () -> {
              out.println("anyOf-2");
              return 11;
            }, () -> {
              throw new AssertionFailedError("shouldn't executed");
            });
        out.println(format("any-returned=%d",r));
        assertEquals(11, r);
      });
      out.println("start");
      x2.head.resumeTop(2);
      out.println("x2-set");
    }
    out.println("# something returned after resuming, suspended after cancel");
    {
      final var x1 = new Unwind();
      final var x2 = new Unwind();
      final var y = new Unwind();
      CRunnable.brackets(() -> {
        final var r = Concurrency.anyOf(
            () -> {
              try {
                out.println("anyOf-1");
                return brk(x1);
              } catch (CancellationException e) {
                out.println("cancelled");
              }
              final int t = brk(x2);
              out.println(format("t recv: %d",t));
              assertEquals(2, t);
              return t;
            },
            () -> {
              out.println("anyOf-2");
              final int t = brk(y);
              out.println(format("anyOf-2: t=%d",t));
              return 10 + t;
            });
        out.println(format("any-returned=%d",r));
        assertEquals(11, r);
      });
      out.println("start");
      y.head.resumeTop(1);
      out.println("y-set");
      x2.head.resumeTop(2);
      out.println("x2-set");
    }
  }

  @Test
  void coopAnyOtherCanceledAfterException() {
    out.println("# something thrown immediately");
    {
      final var x = new Unwind();
      CRunnable.brackets(() -> {
        try {
          Concurrency.anyOf(
              () -> {
                out.println("anyOf-1");
                try {
                  return brk(x);
                } catch (CancellationException e) {
                  out.println("cancelled");
                  throw e;
                }
              },
              () -> {
                out.println("anyOf-2");
                throw new RuntimeException("test1");
              }, () -> {
                throw new AssertionFailedError("shouldn't executed");
              });
        } catch (RuntimeException e) {
          out.println(format("exception: %s",e.getMessage()));
          assertEquals("test1", e.getMessage());
          return;
        }
        throw new AssertionFailedError("shouldn't be executed");
      });
    }
    out.println("# something thrown after resuming");
    {
      final var x = new Unwind();
      final var y = new Unwind();
      CRunnable.brackets(() -> {
        try {
          Concurrency.anyOf(
              () -> {
                out.println("anyOf-1");
                try {
                  return brk(x);
                } catch (CancellationException e) {
                  out.println("cancelled");
                  throw e;
                }
              },
              () -> {
                out.println("anyOf-2");
                final int t = brk(y);
                out.println(format("anyOf-2: t=%d",t));
                throw new RuntimeException(format("test%d",t));
              });
        } catch (RuntimeException t) {
          out.println(format("throw %s",t.getMessage()));
          assertEquals("test2", t.getMessage());
          return;
        }
        throw new AssertionFailedError("shouldn't be executed");
      });
      y.head.resumeTop(2);
    }
    out.println("# something thrown immediately, supeended after cancel");
    {
      final var x1 = new Unwind();
      final var x2 = new Unwind();
      CRunnable.brackets(() -> {
        try {
          Concurrency.anyOf(
              () -> {
                out.println("anyOf-1");
                try {
                  return brk(x1);
                } catch (CancellationException e) {
                  out.println("cancelled");
                }
                final int t = brk(x2);
                out.println(format("t recv: %d",t));
                assertEquals(2, t);
                return t;
              },
              () -> {
                out.println("anyOf-2");
                throw new RuntimeException("test3");
              }, () -> {
                throw new AssertionFailedError("shouldn't executed");
              });
        } catch (RuntimeException e) {
          out.println(format("exception %s",e.getMessage()));
          assertEquals("test3", e.getMessage());
          return;
        }
        throw new AssertionFailedError("shouldn't be executed");
      });
      out.println("start");
      x2.head.resumeTop(2);
      out.println("x2-set");
    }
    out.println("# something returned after resuming, suspended after cancel");
    {
      final var x1 = new Unwind();
      final var x2 = new Unwind();
      final var y = new Unwind();
      CRunnable.brackets(() -> {
        try {
          Concurrency.anyOf(
              () -> {
                out.println("anyOf-1");
                try {
                  return brk(x1);
                } catch (CancellationException e) {
                  out.println("cancelled");
                }
                final int t = brk(x2);
                out.println(format("t recv: %d",t));
                assertEquals(2, t);
                return t;
              },
              () -> {
                out.println("anyOf-2");
                final int t = brk(y);
                return t;
              });
        } catch (RuntimeException e) {
          out.println(format("exception %s",e.getMessage()));
          assertEquals("test4", e.getMessage());
          return;
        }
        throw new AssertionFailedError("shouldn't be executed");
      });
      out.println("start");
      y.head.resumeThrowTop(new RuntimeException("test4"));
      out.println("y-set");
      x2.head.resumeTop(2);
      out.println("x2-set");
    }
  }
}
