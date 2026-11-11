package dev.mmlf.common;
import dev.mmlf.common.GenerationInstructions.Write;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class GenerationInstructionsTest {
 private static final String SOLID=new StandInSpec(true,0,StandInSpec.NONE,true).encode();
 private static final String PLANT=new StandInSpec(false,0,StandInSpec.WATER,false).encode();
 @Test void buildsSetsAndClaimsWithTheirStandInsAndSkipsUnchanged() {
  var out=GenerationInstructions.build(List.of(
   new Write(1,"minecraft:stone","mod:ore",true,SOLID),
   new Write(2,"minecraft:air","mod:crop",true,PLANT),
   new Write(3,"minecraft:air","minecraft:oak_log[axis=y]",false),
   new Write(4,"minecraft:grass_block[snowy=false]","minecraft:grass_block[snowy=false]",false)));
  assertEquals(List.of("C|1|minecraft:stone|"+SOLID,"C|2|minecraft:air|"+PLANT,"S|3|minecraft:air|minecraft:oak_log[axis=y]"),out);
 }
 @Test void appliesOnlyWhereThePrimaryStillHasTheExpectedState() {
  Map<Long,String> primary=Map.of(1L,"minecraft:stone",2L,"minecraft:iron_ore",3L,"minecraft:air",5L,"minecraft:dirt");
  var outcome=GenerationInstructions.decide(List.of(
   "C|1|minecraft:stone|"+SOLID,               // still stone: the stand-in takes its place
   "C|2|minecraft:stone|"+SOLID,               // the primary's own ore came first: rejected, reported
   "S|3|minecraft:air|minecraft:oak_log[axis=y]",
   "S|5|*|minecraft:air",                      // a block of the primary's mods: never matched
   "garbage"),p->primary.getOrDefault(p,"minecraft:air"));
  assertEquals(Map.of(1L,GenerationInstructions.STAND_IN+SOLID,3L,"minecraft:oak_log[axis=y]"),outcome.apply());
  assertEquals(List.of(2L),outcome.rejectedClears());
  assertEquals(3,outcome.rejected());
 }
 @Test void aClaimOverAirIsRejectedWhereThePrimaryPlacedSomethingSince() {
  var outcome=GenerationInstructions.decide(List.of("C|1|minecraft:air|"+PLANT,"C|2|minecraft:air|"+PLANT),p->p==1?"minecraft:poppy":"minecraft:air");
  assertEquals(Map.of(2L,GenerationInstructions.STAND_IN+PLANT),outcome.apply());
  assertEquals(List.of(1L),outcome.rejectedClears());
 }
 @Test void aClaimWhereTheStandInAlreadyIsIsDoneAndAnythingElseThereRejected() {
  var outcome=GenerationInstructions.decide(List.of("C|1|minecraft:air|"+PLANT,"S|1|minecraft:air|minecraft:stone"),p->GenerationInstructions.OWNED);
  assertTrue(outcome.apply().isEmpty());
  assertTrue(outcome.rejectedClears().isEmpty(),"the position is the background's already: nothing to undo");
  assertEquals(1,outcome.rejected());
 }
 @Test void laterInstructionsForAPositionSeeEarlierOnes() {
  var outcome=GenerationInstructions.decide(List.of("S|7|minecraft:air|minecraft:water[level=0]","S|7|minecraft:water[level=0]|minecraft:ice"),p->"minecraft:air");
  assertEquals(Map.of(7L,"minecraft:ice"),outcome.apply());
  assertEquals(0,outcome.rejected());
  var claimed=GenerationInstructions.decide(List.of("C|8|minecraft:air|"+PLANT,"C|8|minecraft:air|"+SOLID),p->"minecraft:air");
  assertEquals(Map.of(8L,GenerationInstructions.STAND_IN+PLANT),claimed.apply(),"the second claim finds the first one's stand-in");
 }
 @Test void parsesAllKindsIncludingClaimsSavedWithoutAStandIn() {
  assertEquals(new GenerationInstructions.Instruction(true,9,"minecraft:stone",null),GenerationInstructions.parse("C|9|minecraft:stone"));
  assertEquals(new GenerationInstructions.Instruction(true,9,"minecraft:stone",SOLID),GenerationInstructions.parse("C|9|minecraft:stone|"+SOLID));
  assertEquals(new GenerationInstructions.Instruction(false,-4,"minecraft:air","minecraft:sand"),GenerationInstructions.parse("S|-4|minecraft:air|minecraft:sand"));
  assertNull(GenerationInstructions.parse("S|x|a|b"));
  assertNull(GenerationInstructions.parse("C|1"));
  assertEquals(Map.of(9L,GenerationInstructions.STAND_IN+StandInSpec.DEFAULT.encode()),GenerationInstructions.decide(List.of("C|9|minecraft:stone"),p->"minecraft:stone").apply());
 }
}
