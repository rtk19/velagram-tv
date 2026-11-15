package com.velagram.tv;
import org.junit.Test;
import static org.junit.Assert.*;
public class TemporaryMediaTest {
 @Test public void storagePressureBoundsTemporaryBytesWithoutDiskFilling() {
   assertEquals(0, TemporaryMedia.budget(500L << 20, 100L << 20));
   assertEquals(122L << 20, TemporaryMedia.budget(500L << 20, 500L << 20));
   assertEquals(100L << 20, TemporaryMedia.budget(100L << 20, 5000L << 20));
   assertEquals(500L << 20, TemporaryMedia.budget(0, 5000L << 20));
 }
}
