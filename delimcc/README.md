# Multi-prompt delimited continuations

[![javadoc](https://javadoc.io/badge2/io.github.javactrl/javactrl-delimcc/javadoc.svg)](https://javadoc.io/doc/io.github.javactrl/javactrl-delimcc)

This library implements operators from 
[A Monadic Framework for Delimited Continuations](https://cs.indiana.edu/~dyb/pubs/monadicDC.pdf) paper.

It's an alternative to resumable exceptions in the core package. Continuations using this implementation will utilize many lambda functions (or anonymous classes) instead of exception handlers like in the core package.

Lambdas work better for generics because exceptions aren't allowed to have a generic type in Java, and exceptions will require more unchecked casts. However, the exception handlers can change any variable in scope, while lambdas can access only effectively final variables in Java. Note, this doesn't mean continuations with exception handlers hack some safety restrictions of Java, as they always create a shallow clone of the call frames.

Each operation in this library uses the core package, and each operator is only a couple of trivial lines of code with exception handlers.

## Usage

Use the instrumenter from the core package and add this library as a dependecy in your project. See details in javadoc.

TODO:
