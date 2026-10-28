package dev.mmlf.common;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ProjectionPlanesTest {
 @Test void recoversFarPlaneRatherThanNearPlane() {
  float near=0.05f,far=256f;
  float a=(far+near)/(near-far), b=2*far*near/(near-far);
  assertEquals(far,ProjectionPlanes.far(a,b),0.1);
  assertNotEquals(near,ProjectionPlanes.far(a,b),0.01);
 }
 @Test void guardsInfiniteAndInvalidProjection() {
  assertEquals(1024,ProjectionPlanes.far(-1,-0.1f));
  assertEquals(1024,ProjectionPlanes.far(Float.NaN,1));
  assertEquals(1024,ProjectionPlanes.far(0,-1));
 }
}
