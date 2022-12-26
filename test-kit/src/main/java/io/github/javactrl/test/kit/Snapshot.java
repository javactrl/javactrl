package io.github.javactrl.test.kit;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.lang.annotation.ElementType;
import java.lang.annotation.RetentionPolicy;

import org.junit.jupiter.api.extension.ExtendWith;

@Target({ ElementType.FIELD  })
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(OutputSnapshotExtension.class)
public @interface Snapshot {
}
