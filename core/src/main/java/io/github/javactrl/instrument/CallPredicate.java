package io.github.javactrl.instrument;

/**
 * This is a predicate specifying if the method must be instrumented
 */
@FunctionalInterface
public interface CallPredicate {
    /**
     * This method is overriden to select a class to instrument
     * 
     * @param owner Class name (internal)
     * @param name Method name
     * @return <code>true</code> if the method must be instrumented
     */
    boolean test(String owner, String name);
}
