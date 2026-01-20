# seedcracker_java
```
**THIS CRACKS STRUCTURE SEEDS NOT THE WORLD SEED**
**THIS CRACKS STRUCTURE SEEDS NOT THE WORLD SEED**
**THIS CRACKS STRUCTURE SEEDS NOT THE WORLD SEED**
```

**TO RUN:**
```
#change directory to src
java StructureSeedCracker constraints.txt
```
Example run output:
```
java StructureSeedCracker constraints.txt
--- Standard Structure Seed Search (CPU) ---

--- Stage 1: Filtering Lower 20-bit seed patterns ---
Using 2 shipwreck(s) to filter 20-bit candidates...
[                              ] 1/1048576 (0.00%) | 0.0s elapsed | 129.7k/s | ETA 8.1s
Found 4444 potential 20-bit candidates.

--- Stage 2: Using REVERSING Approach ---
Using Portal at [52,17] as anchor.
[==============================] 4443/4444 (99.98%) | 225.5s elapsed | 0.0k/s | ETA 0.1ss

--- Search Complete in 225.631285125 seconds ---
Found 3 valid seed(s). Writing to found_seeds.txt...
Done.
```
**TO DEBUG/CHECK:**
```
#change directory to src
java StructureSeedCracker <seed>
```
Expample debug output:
```
java StructureSeedCracker 203065802765054

--- Debug mode for structure seed 203065802765054 ---
Constraint 1 (SHIPWRECK @ [-22,-42]):
  -> PASS
Constraint 2 (SHIPWRECK @ [112,89]):
  -> PASS
Constraint 3 (RUINED_PORTAL @ [52,17]):
  [Portal] reg=(1,0), offset=(12,17), gen=(52,17)
  [Portal] category=MOUNTAINS
  [Portal] MOUNTAINS branch: f1=0.42640507
  [Portal] giantRoll=0.9590498 → NORMAL
  [Portal] type=PORTAL_1 (expected PORTAL_1)
  [Portal] rotation=CLOCKWISE_180 (expected CLOCKWISE_180)
  [Portal] mirror=FRONT_BACK (expected FRONT_BACK)
  -> PASS
All constraints satisfied ✅
```
Example input:
```
-54, -14, COUNTERCLOCKWISE_90, sideways_fronthalf, Ocean #shipwreck

112, 89, CLOCKWISE_180, rightsideup_full_degraded, Beached #shipwreck

55, -9, CLOCKWISE_180, taiga_meeting_point_1, 3, no #village

52, 17, CLOCKWISE_180, portal_1, yes, 1 #portal

PORTALCHEST: 1338 947 1 #portal chest (world coords, biome category)

```

**FAQs**
1) What are the constraints for villages?
```
<chunkX>, <chunkZ>, <rotation>, <variant of origin>, <biome>  (1 = plains, 2 = snowy, 3 = taiga, 4 = savanna, 5 = desert), <abandoned> (yes/no)
#refer to above for example
```

3) What are the constraints for ruined portals?
```
<chunkX>, <chunkZ>, <rotation>, <variant>, <mirrored> (yes/no), <biome> (1 = most biomes, 2 = desert, nether (after 1.18), swamp (not mangrove though), ocean, 3 = jungle (any kind of portal that has vines growing on it"
#refer to above for example
```

4) What are the constraints for portal chest locations?
```
PORTALCHEST: <worldX> <worldZ> <biome> (1 = most biomes, 2 = desert, nether (after 1.18), swamp (not mangrove though), ocean, 3 = jungle)
#world coords are the block coords from F3, not chunk coords
```

5) What are the constraints for shipwrecks?
```
<chunkX>, <chunkZ>, <rotation>, <variant>, <condition> (beached/ocean)
#refer to above for example
```

**IMPORTANT**
If you have the pillarseed, add it as the last line. If not just leave it blank, its optional.

Shipwrecks are extremely helpful for filtering and speed up the program by a lot so try to get two.
