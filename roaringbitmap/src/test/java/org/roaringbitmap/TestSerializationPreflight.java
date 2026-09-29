package org.roaringbitmap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class TestSerializationPreflight {

  private static final int ARRAY_KEY = 0;
  private static final int BITMAP_KEY = 1;
  private static final int RUN_KEY = 2;
  private static final int SECOND_ARRAY_KEY = 3;
  private static final int NO_OFFSET_THRESHOLD_CASE = 4;

  private static int[] scatteredValues() {
    return new int[] {1, 5, 17, 1000, 4000, 9999, 20000, 33333, 44444, 60000};
  }

  private static void addAlternatingValues(RoaringBitmap bitmap, int highKey) {
    for (int low = 0; low < 1 << 16; low += 2) {
      bitmap.add((highKey << 16) | low);
    }
  }

  private static void addScatteredValues(RoaringBitmap bitmap, int highKey) {
    for (int low : scatteredValues()) {
      bitmap.add((highKey << 16) | low);
    }
  }

  private static void bitmapAdd(RoaringBitmap bitmap, int highKey, int low) {
    bitmap.add((highKey << 16) | low);
  }

  private static RoaringBitmap bitmapWithAllContainerTypes(int containerCount) {
    RoaringBitmap bitmap = new RoaringBitmap();
    addScatteredValues(bitmap, ARRAY_KEY);
    addAlternatingValues(bitmap, BITMAP_KEY);
    bitmap.add((long) (RUN_KEY << 16) + 100, (long) (RUN_KEY << 16) + 201);
    bitmap.add((long) (RUN_KEY << 16) + 300, (long) (RUN_KEY << 16) + 401);
    if (containerCount >= NO_OFFSET_THRESHOLD_CASE) {
      int[] extras = {2, 50, 777, 8888, 50000};
      for (int low : extras) {
        bitmap.add((SECOND_ARRAY_KEY << 16) | low);
      }
    }
    bitmap.runOptimize();
    return bitmap;
  }

  private static ByteBuffer serialize(RoaringBitmap bitmap) {
    ByteBuffer buffer =
        ByteBuffer.allocate(bitmap.serializedSizeInBytes()).order(ByteOrder.LITTLE_ENDIAN);
    bitmap.serialize(buffer);
    return buffer;
  }

  private static byte[] serialized(RoaringBitmap bitmap) {
    return serialize(bitmap).array();
  }

  private static RoaringBitmap decode(ByteBuffer buffer) throws Exception {
    RoaringBitmap bitmap = new RoaringBitmap();
    bitmap.deserialize(buffer);
    return bitmap;
  }

  private static int leGetInt(byte[] data, int offset) {
    return (data[offset] & 0xFF)
        | ((data[offset + 1] & 0xFF) << 8)
        | ((data[offset + 2] & 0xFF) << 16)
        | ((data[offset + 3] & 0xFF) << 24);
  }

  private static void lePutInt(byte[] data, int offset, int value) {
    data[offset] = (byte) value;
    data[offset + 1] = (byte) (value >>> 8);
    data[offset + 2] = (byte) (value >>> 16);
    data[offset + 3] = (byte) (value >>> 24);
  }

  private static char leGetChar(byte[] data, int offset) {
    return (char) ((data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8));
  }

  private static void lePutChar(byte[] data, int offset, int value) {
    data[offset] = (byte) value;
    data[offset + 1] = (byte) (value >>> 8);
  }

  private static void assertValid(SerializationPreflight.Report report) {
    assertTrue(report.isValid(), report.toString());
    assertEquals(-1L, report.getByteOffset());
    assertEquals(-1, report.getContainerIndex());
  }

  private static void assertProblem(
      SerializationPreflight.Report report,
      SerializationPreflight.Problem problem,
      long byteOffset,
      int containerIndex) {
    assertFalse(report.isValid(), "expected a problem");
    assertEquals(problem, report.getProblem());
    assertEquals(byteOffset, report.getByteOffset());
    assertEquals(containerIndex, report.getContainerIndex());
  }

  @Test
  public void validBitmapWithThreeContainerKindsPassesAndDecodes() throws Exception {
    RoaringBitmap original = bitmapWithAllContainerTypes(4);
    byte[] data = serialized(original);

    assertValid(SerializationPreflight.inspect(ByteBuffer.wrap(data)));
    assertValid(
        SerializationPreflight.inspect(ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)));

    RoaringBitmap decoded = decode(ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN));
    assertEquals(original, decoded);
    assertEquals(original.getCardinality(), decoded.getCardinality());
  }

  @Test
  public void validBitmapWithoutRunContainersPassesAndDecodes() throws Exception {
    RoaringBitmap original = new RoaringBitmap();
    addScatteredValues(original, ARRAY_KEY);
    addAlternatingValues(original, BITMAP_KEY);
    // deliberately no runOptimize(): exercises the no-run cookie format

    byte[] data = serialized(original);
    assertValid(SerializationPreflight.inspect(ByteBuffer.wrap(data)));
    assertEquals(original, decode(ByteBuffer.wrap(data)));
  }

  @Test
  public void emptyBitmapPassesAndDecodes() throws Exception {
    byte[] data = serialized(new RoaringBitmap());
    assertValid(SerializationPreflight.inspect(ByteBuffer.wrap(data)));
    assertEquals(new RoaringBitmap(), decode(ByteBuffer.wrap(data)));
  }

  @Test
  public void invalidCookieIsRejected() {
    byte[] data = serialized(bitmapWithAllContainerTypes(4));
    data[0] = 0;
    data[1] = 0;
    assertProblem(
        SerializationPreflight.inspect(ByteBuffer.wrap(data)),
        SerializationPreflight.Problem.INVALID_COOKIE,
        0,
        -1);
  }

  @Test
  public void invalidContainerCountIsRejected() {
    byte[] data = serialized(new RoaringBitmap());
    lePutInt(data, 4, 65537);
    assertProblem(
        SerializationPreflight.inspect(ByteBuffer.wrap(data)),
        SerializationPreflight.Problem.INVALID_CONTAINER_COUNT,
        4,
        -1);
  }

  @Test
  public void nonIncreasingKeyIsRejected() {
    byte[] data = serialized(bitmapWithAllContainerTypes(4));
    // key/cardinality pairs start at offset 5; the second key is at offset 9
    lePutChar(data, 9, ARRAY_KEY);
    assertProblem(
        SerializationPreflight.inspect(ByteBuffer.wrap(data)),
        SerializationPreflight.Problem.KEY_NOT_INCREASING,
        9,
        1);
  }

  @Test
  public void overlappingOffsetIsRejected() {
    byte[] data = serialized(bitmapWithAllContainerTypes(4));
    // cookie + 1 marker byte + 16 bytes of key/cardinality pairs
    int offsetsStart = 21;
    int firstOffset = leGetInt(data, offsetsStart);
    lePutInt(data, offsetsStart + 4, firstOffset);
    assertProblem(
        SerializationPreflight.inspect(ByteBuffer.wrap(data)),
        SerializationPreflight.Problem.INVALID_OFFSET,
        offsetsStart + 4,
        1);
  }

  @Test
  public void overlappingOffsetInNoRunCookieIsRejected() {
    RoaringBitmap original = new RoaringBitmap();
    addScatteredValues(original, ARRAY_KEY);
    addAlternatingValues(original, BITMAP_KEY);
    byte[] data = serialized(original);
    // no-run cookie: 8 byte header + 8 bytes of key/cardinality pairs
    int offsetsStart = 16;
    int firstOffset = leGetInt(data, offsetsStart);
    lePutInt(data, offsetsStart + 4, firstOffset);
    assertProblem(
        SerializationPreflight.inspect(ByteBuffer.wrap(data)),
        SerializationPreflight.Problem.INVALID_OFFSET,
        offsetsStart + 4,
        1);
  }

  private static int runContainerOffset(byte[] data) {
    int offsetsStart = 4 + 1 + 4 * 4;
    return leGetInt(data, offsetsStart + 4 * 2);
  }

  @Test
  public void overlappingRunIsRejected() {
    byte[] data = serialized(bitmapWithAllContainerTypes(4));
    int runOffset = runContainerOffset(data);
    assertEquals(2, leGetChar(data, runOffset));
    // extend the first run [100, 201] so it reaches the second run start (300)
    lePutChar(data, runOffset + 4, 300 - 100);
    assertProblem(
        SerializationPreflight.inspect(ByteBuffer.wrap(data)),
        SerializationPreflight.Problem.RUN_OVERLAP,
        runOffset + 6,
        2);
  }

  @Test
  public void runExtendingPastContainerSpaceIsRejected() {
    byte[] data = serialized(bitmapWithAllContainerTypes(4));
    int runOffset = runContainerOffset(data);
    // second run starts at 300; with length 0xFFFF it reaches past the 16 bit space
    lePutChar(data, runOffset + 8, 0xFFFF);
    assertProblem(
        SerializationPreflight.inspect(ByteBuffer.wrap(data)),
        SerializationPreflight.Problem.RUN_OUT_OF_RANGE,
        runOffset + 6,
        2);
  }

  @Test
  public void wrongRunCardinalityIsRejected() {
    byte[] data = serialized(bitmapWithAllContainerTypes(4));
    int runOffset = runContainerOffset(data);
    // shrink the first run to [100, 150]: it stays disjoint from run two but cardinality changes
    lePutChar(data, runOffset + 4, 50);
    assertProblem(
        SerializationPreflight.inspect(ByteBuffer.wrap(data)),
        SerializationPreflight.Problem.CARDINALITY_MISMATCH,
        runOffset,
        2);
  }

  @Test
  public void wrongBitmapCardinalityIsRejected() {
    byte[] data = serialized(bitmapWithAllContainerTypes(4));
    int bitmapOffset = leGetInt(data, 25);
    // even values are present: set an absent odd bit without updating declared cardinality
    data[bitmapOffset] |= 0x02;
    assertProblem(
        SerializationPreflight.inspect(ByteBuffer.wrap(data)),
        SerializationPreflight.Problem.CARDINALITY_MISMATCH,
        bitmapOffset,
        1);
  }

  @Test
  public void wrongArrayCardinalityIsRejected() {
    byte[] data = serialized(bitmapWithAllContainerTypes(4));
    int arrayOffset = leGetInt(data, 21);
    // swap the cardinality field with the value at the first array slot: the declared payload
    // then reads one real value plus four trailing zero bytes, which are not sorted/distinct
    lePutChar(data, 7, leGetChar(data, 7) + 1);
    lePutChar(data, arrayOffset + 2, 0);
    assertProblem(
        SerializationPreflight.inspect(ByteBuffer.wrap(data)),
        SerializationPreflight.Problem.ARRAY_NOT_SORTED,
        arrayOffset + 2,
        0);
  }

  @Test
  public void unsortedArrayValuesAreRejected() {
    byte[] data = serialized(bitmapWithAllContainerTypes(4));
    int arrayOffset = leGetInt(data, 21);
    lePutChar(data, arrayOffset, 60000);
    assertProblem(
        SerializationPreflight.inspect(ByteBuffer.wrap(data)),
        SerializationPreflight.Problem.ARRAY_NOT_SORTED,
        arrayOffset + 2,
        0);
  }

  @Test
  public void truncatedPayloadIsRejected() {
    byte[] data = serialized(bitmapWithAllContainerTypes(4));
    int bitmapOffset = leGetInt(data, 25);
    int truncatedLength = bitmapOffset + 10;
    ByteBuffer slice = ByteBuffer.wrap(data, 0, truncatedLength);
    assertProblem(
        SerializationPreflight.inspect(slice),
        SerializationPreflight.Problem.TRUNCATED,
        truncatedLength,
        1);
  }

  @Test
  public void truncatedHeaderIsRejected() {
    byte[] data = serialized(bitmapWithAllContainerTypes(4));
    ByteBuffer slice = ByteBuffer.wrap(data, 0, 10);
    assertProblem(
        SerializationPreflight.inspect(slice),
        SerializationPreflight.Problem.TRUNCATED,
        10,
        -1);
  }

  @Test
  public void truncatedCookieIsRejected() {
    ByteBuffer slice = ByteBuffer.wrap(new byte[] {1, 2});
    assertProblem(
        SerializationPreflight.inspect(slice),
        SerializationPreflight.Problem.TRUNCATED,
        2,
        -1);
  }

  @Test
  public void smallBitmapWithoutOffsetTablePassesAndDecodes() throws Exception {
    RoaringBitmap original = bitmapWithAllContainerTypes(3);
    byte[] data = serialized(original);
    assertValid(SerializationPreflight.inspect(ByteBuffer.wrap(data)));
    assertEquals(original, decode(ByteBuffer.wrap(data)));

    // locate the run container sequentially: the small run format omits offsets
    int position = 4 + 1 + 4 * 3; // cookie + marker + key/cardinality pairs
    int arrayCardinality = leGetChar(data, 5 + 2) + 1;
    position += arrayCardinality * 2;
    position += 8192; // bitmap container
    lePutChar(data, position + 4, 300 - 100); // overlap the two runs
    assertProblem(
        SerializationPreflight.inspect(ByteBuffer.wrap(data)),
        SerializationPreflight.Problem.RUN_OVERLAP,
        position + 6,
        2);
  }

  @Test
  public void readOnlyBufferIsSupported() {
    byte[] data = serialized(bitmapWithAllContainerTypes(4));
    ByteBuffer readOnly = ByteBuffer.wrap(data).asReadOnlyBuffer();
    assertValid(SerializationPreflight.inspect(readOnly));
  }

  @Test
  public void callerBufferStateIsPreserved() {
    byte[] data = serialized(bitmapWithAllContainerTypes(4));
    ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
    int initialPosition = buffer.position();
    int initialLimit = buffer.limit();
    assertValid(SerializationPreflight.inspect(buffer));
    assertEquals(initialPosition, buffer.position());
    assertEquals(initialLimit, buffer.limit());
    assertEquals(ByteOrder.BIG_ENDIAN, buffer.order());
  }

  @Test
  public void scanLimitIsEnforced() {
    byte[] data = serialized(bitmapWithAllContainerTypes(4));
    assertValid(SerializationPreflight.inspect(ByteBuffer.wrap(data), data.length));
    assertProblem(
        SerializationPreflight.inspect(ByteBuffer.wrap(data), data.length - 1L),
        SerializationPreflight.Problem.SCAN_LIMIT_EXCEEDED,
        data.length - 1L,
        3);
    assertThrows(
        IllegalArgumentException.class,
        () -> SerializationPreflight.inspect(ByteBuffer.wrap(data), -1L));
  }

  @Test
  public void reportedOffsetsAreRelativeToSliceOrigin() {
    byte[] data = serialized(bitmapWithAllContainerTypes(4));
    int runOffset = runContainerOffset(data);
    lePutChar(data, runOffset + 4, 300 - 100); // overlapping runs

    int prefix = 37;
    byte[] embedded = new byte[prefix + data.length + 11];
    System.arraycopy(data, 0, embedded, prefix, data.length);
    ByteBuffer embeddedBuffer = ByteBuffer.wrap(embedded);
    embeddedBuffer.position(prefix);
    ByteBuffer slice = embeddedBuffer.slice();

    assertProblem(
        SerializationPreflight.inspect(slice),
        SerializationPreflight.Problem.RUN_OVERLAP,
        runOffset + 6,
        2);
  }

  @Test
  public void trailingBytesDoNotInvalidateTheSlice() throws Exception {
    RoaringBitmap original = bitmapWithAllContainerTypes(4);
    byte[] serialized = serialized(original);
    ByteBuffer withTrailer = ByteBuffer.allocate(serialized.length + 20);
    withTrailer.put(serialized);
    withTrailer.putInt(0x12345678);
    withTrailer.flip();
    assertValid(SerializationPreflight.inspect(withTrailer));
    assertEquals(original, decode(withTrailer));
  }

  @Test
  public void randomBitmapsPassPreflightAndDecodeIdentically() throws Exception {
    java.util.Random random = new java.util.Random(0x25088);
    for (int iteration = 0; iteration < 30; ++iteration) {
      RoaringBitmap original = new RoaringBitmap();
      int containerCount = 1 + random.nextInt(8);
      int[] highKeys = new int[containerCount];
      int key = 0;
      for (int k = 0; k < containerCount; ++k) {
        key += (k == 0 ? 0 : 1) + random.nextInt(5);
        highKeys[k] = key;
        int mode = random.nextInt(3);
        if (mode == 0) {
          int cardinality = 1 + random.nextInt(4000);
          for (int added = 0; added < cardinality; ) {
            int low = random.nextInt(1 << 16);
            if (!original.contains((highKeys[k] << 16) | low)) {
              bitmapAdd(original, highKeys[k], low);
              ++added;
            }
          }
        } else if (mode == 1) {
          int densityExponent = random.nextInt(2); // dense or sparse bitmap container
          int step = densityExponent == 0 ? 2 : 3;
          for (int low = random.nextInt(step); low < 1 << 16; low += step) {
            bitmapAdd(original, highKeys[k], low);
          }
        } else {
          int runCount = 1 + random.nextInt(4);
          int cursor = random.nextInt(100);
          for (int r = 0; r < runCount && cursor < 60000; ++r) {
            int runLength = 1 + random.nextInt(200);
            original.add(
                (long) (highKeys[k] << 16) + cursor,
                (long) (highKeys[k] << 16) + cursor + runLength);
            cursor += runLength + 1 + random.nextInt(50);
          }
        }
      }
      if (iteration % 2 == 0) {
        original.runOptimize();
      }

      byte[] data = serialized(original);
      assertValid(SerializationPreflight.inspect(ByteBuffer.wrap(data)));
      RoaringBitmap decoded = decode(ByteBuffer.wrap(data));
      assertEquals(original, decoded, "iteration " + iteration);
    }
  }
}
