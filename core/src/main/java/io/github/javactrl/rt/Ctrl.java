package io.github.javactrl.rt;

import java.lang.annotation.Documented;
import java.lang.annotation.Target;
import java.lang.annotation.ElementType;

/** Marks classes for instrumentation */
@Documented
@Target({ElementType.TYPE, ElementType.FIELD})
public @interface Ctrl {
}
