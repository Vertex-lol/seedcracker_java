import java.io.*;
import java.util.*;
import java.util.stream.Collectors;

public class StructureSeedCracker {

    // =======================================================================
    // 1. Minecraft LCG & Constants
    // =======================================================================

    private static final long LCG_MULT = 25214903917L;
    private static final long LCG_ADD = 11L;
    private static final long XOR_MASK = 25214903917L;
    private static final long MASK_48 = (1L << 48) - 1;
    private static final long LCG_MULT_INV = 246154705703781L; // modular inverse

    private static final long MULT_A = 341873128712L;
    private static final long MULT_B = 132897987541L;

    private static final long PILLAR_MULT = 1540035429L;
    private static final long PILLAR_ADD = 239479465L;

    // Shipwreck Constants
    private static final int SHIPWRECK_SPACING = 24;
    private static final int SHIPWRECK_SEPARATION = 4;
    private static final long SHIPWRECK_SALT = 165745295L;
    private static final int OCEAN_TYPE_COUNT = 20;
    private static final int BEACHED_TYPE_COUNT = 11;

    // Ruined Portal Constants
    private static final int PORTAL_SPACING = 40;
    private static final int PORTAL_SEPARATION = 15;
    private static final long RUINED_PORTAL_SALT = 34222645L;

    // Village Constants
    private static final int VILLAGE_SPACING = 34;
    private static final int VILLAGE_SEPARATION = 8;
    private static final long VILLAGE_SALT = 10387312L;

    // Shipwreck type lookups (CPU equivalent of __constant__ arrays)
    private static ShipwreckType[] STRUCTURE_LOCATION_OCEAN = new ShipwreckType[OCEAN_TYPE_COUNT];
    private static ShipwreckType[] STRUCTURE_LOCATION_BEACHED = new ShipwreckType[BEACHED_TYPE_COUNT];

    // Debug flag for verbose portal logging
    private static boolean DEBUG_VERBOSE = false;

    // =======================================================================
    // 2. Enums & Data Structures
    // =======================================================================

    enum BlockRotation {
        NONE, CLOCKWISE_90, CLOCKWISE_180, COUNTERCLOCKWISE_90
    }

    enum BlockMirror {
        NONE, FRONT_BACK
    }

    // NOTE: we treat this as 0-based internally, and map user numbers manually.
    enum BiomeCategory {
        MOUNTAINS(0),
        DESERT(1),
        JUNGLE(2);

        final int id;

        BiomeCategory(int id) { this.id = id; }

        /**
         * Map user CLI values to RNG branches:
         *  1 = "most" category      → MOUNTAINS branch
         *  2 = desert/ocean/swamp   → JUNGLE branch (always 1 extra float)
         *  3 = jungle/vines         → DESERT branch (no extra float)
         */
        static BiomeCategory fromInt(int v) {
            switch (v) {
                case 1: return MOUNTAINS;
                case 2: return JUNGLE;
                case 3: return DESERT;
                default: throw new IllegalArgumentException("Invalid BiomeCategory: " + v);
            }
        }
    }

    enum PortalType {
        PORTAL_1, PORTAL_2, PORTAL_3, PORTAL_4, PORTAL_5,
        PORTAL_6, PORTAL_7, PORTAL_8, PORTAL_9, PORTAL_10,
        GIANT_PORTAL_1, GIANT_PORTAL_2, GIANT_PORTAL_3
    }

    enum ShipwreckType {
        INVALID,
        RIGHTSIDEUP_BACKHALF, RIGHTSIDEUP_BACKHALF_DEGRADED,
        RIGHTSIDEUP_FRONTHALF, RIGHTSIDEUP_FRONTHALF_DEGRADED,
        RIGHTSIDEUP_FULL, RIGHTSIDEUP_FULL_DEGRADED,
        SIDEWAYS_BACKHALF, SIDEWAYS_BACKHALF_DEGRADED,
        SIDEWAYS_FRONTHALF, SIDEWAYS_FRONTHALF_DEGRADED,
        SIDEWAYS_FULL, SIDEWAYS_FULL_DEGRADED,
        UPSIDEDOWN_BACKHALF, UPSIDEDOWN_BACKHALF_DEGRADED,
        UPSIDEDOWN_FRONTHALF, UPSIDEDOWN_FRONTHALF_DEGRADED,
        UPSIDEDOWN_FULL, UPSIDEDOWN_FULL_DEGRADED,
        WITH_MAST, WITH_MAST_DEGRADED
    }

    enum VillageType {
        PLAINS, DESERT, SAVANNA, TAIGA, SNOWY
    }

    enum VillageStartPiece {
        PLAINS_FOUNTAIN_01, PLAINS_MEETING_POINT_1, PLAINS_MEETING_POINT_2, PLAINS_MEETING_POINT_3,
        DESERT_MEETING_POINT_1, DESERT_MEETING_POINT_2, DESERT_MEETING_POINT_3,
        SAVANNA_MEETING_POINT_1, SAVANNA_MEETING_POINT_2, SAVANNA_MEETING_POINT_3, SAVANNA_MEETING_POINT_4,
        TAIGA_MEETING_POINT_1, TAIGA_MEETING_POINT_2,
        SNOWY_MEETING_POINT_1, SNOWY_MEETING_POINT_2, SNOWY_MEETING_POINT_3,
        UNKNOWN_PIECE
    }

    enum ConstraintType {
        SHIPWRECK, RUINED_PORTAL, VILLAGE, PORTAL_CHEST
    }

    static class RuinedPortalConstraintData {
        BlockRotation rotation;
        BlockMirror mirror;
        PortalType type;
        BiomeCategory category;
    }

    static class PortalChestConstraintData {
        BiomeCategory category;
        List<PortalCandidate> candidates = new ArrayList<>();
    }

    static class PortalCandidate {
        PortalType type;
        BlockRotation rotation;
        BlockMirror mirror;

        PortalCandidate(PortalType type, BlockRotation rotation, BlockMirror mirror) {
            this.type = type;
            this.rotation = rotation;
            this.mirror = mirror;
        }
    }

    static class PortalTemplateData {
        int sizeX;
        int sizeZ;
        int chestX;
        int chestZ;

        PortalTemplateData(int sizeX, int sizeZ, int chestX, int chestZ) {
            this.sizeX = sizeX;
            this.sizeZ = sizeZ;
            this.chestX = chestX;
            this.chestZ = chestZ;
        }
    }

    static class ShipwreckConstraintData {
        BlockRotation rotation;
        ShipwreckType type;
        boolean isBeached;
    }

    static class VillageConstraintData {
        BlockRotation rotation;
        VillageStartPiece piece;
        VillageType type;
        boolean isAbandoned;
    }

    static class Constraint {
        ConstraintType type;
        int chunkX;
        int chunkZ;
        ShipwreckConstraintData shipwreck = new ShipwreckConstraintData();
        RuinedPortalConstraintData portal = new RuinedPortalConstraintData();
        PortalChestConstraintData portalChest = new PortalChestConstraintData();
        VillageConstraintData village = new VillageConstraintData();
    }

    // =======================================================================
    // 3. RNG / LCG helper
    // =======================================================================

    static class StandaloneChunkRand {
        private long seed;

        void setSeed(long s) {
            seed = (s ^ XOR_MASK) & MASK_48;
        }

        int next(int bits) {
            seed = (seed * LCG_MULT + LCG_ADD) & MASK_48;
            return (int) (seed >>> (48 - bits));
        }

        int nextInt(int bound) {
            if (bound <= 0) return 0;
            if ((bound & -bound) == bound) {
                // power of two
                return (int) ((bound * (long) next(31)) >> 31);
            }
            int bits, val;
            do {
                bits = next(31);
                val = bits % bound;
            } while (bits - val + (bound - 1) < 0);
            return val;
        }

        long nextLong() {
            // MUST match java.util.Random exactly:
            // ((long)next(32) << 32) + next(32)
            return ((long) next(32) << 32) + next(32);
        }

        float nextFloat() {
            return next(24) / 16777216.0f;
        }

        void setRegionSeed(long structureSeed, int regionX, int regionZ, long salt) {
            long s = (long) regionX * MULT_A
                    + (long) regionZ * MULT_B
                    + structureSeed
                    + salt;
            setSeed(s);
        }

        void setCarverSeed(long worldSeed, int chunkX, int chunkZ) {
            setSeed(worldSeed);
            long a = nextLong();
            long b = nextLong();
            setSeed((long) chunkX * a ^ (long) chunkZ * b ^ worldSeed);
        }
    }

    // =======================================================================
    // 4. Math helpers
    // =======================================================================

    private static int floorDiv(int a, int n) {
        int r = a / n;
        if ((a % n != 0) && ((a < 0) != (n < 0))) {
            r--;
        }
        return r;
    }

    // =======================================================================
    // 5. Village property calculation
    // =======================================================================

    private static void getVillageProperties(
            VillageStartPiece[] outPiece, BlockRotation[] outRot, boolean[] outAbandoned,
            VillageType type, StandaloneChunkRand rand
    ) {
        outRot[0] = BlockRotation.values()[rand.next(2)];
        outAbandoned[0] = false;
        outPiece[0] = VillageStartPiece.UNKNOWN_PIECE;

        int t;

        switch (type) {
            case PLAINS:
                t = rand.nextInt(204);
                if (t < 50) outPiece[0] = VillageStartPiece.PLAINS_FOUNTAIN_01;
                else if (t < 100) outPiece[0] = VillageStartPiece.PLAINS_MEETING_POINT_1;
                else if (t < 150) outPiece[0] = VillageStartPiece.PLAINS_MEETING_POINT_2;
                else if (t < 200) outPiece[0] = VillageStartPiece.PLAINS_MEETING_POINT_3;
                else {
                    outAbandoned[0] = true;
                    if (t < 201) outPiece[0] = VillageStartPiece.PLAINS_FOUNTAIN_01;
                    else if (t < 202) outPiece[0] = VillageStartPiece.PLAINS_MEETING_POINT_1;
                    else if (t < 203) outPiece[0] = VillageStartPiece.PLAINS_MEETING_POINT_2;
                    else outPiece[0] = VillageStartPiece.PLAINS_MEETING_POINT_3;
                }
                break;

            case DESERT:
                t = rand.nextInt(250);
                if (t < 98) outPiece[0] = VillageStartPiece.DESERT_MEETING_POINT_1;
                else if (t < 196) outPiece[0] = VillageStartPiece.DESERT_MEETING_POINT_2;
                else if (t < 245) outPiece[0] = VillageStartPiece.DESERT_MEETING_POINT_3;
                else {
                    outAbandoned[0] = true;
                    if (t < 247) outPiece[0] = VillageStartPiece.DESERT_MEETING_POINT_1;
                    else if (t < 249) outPiece[0] = VillageStartPiece.DESERT_MEETING_POINT_2;
                    else outPiece[0] = VillageStartPiece.DESERT_MEETING_POINT_3;
                }
                break;

            case SAVANNA:
                t = rand.nextInt(459);
                if (t < 100) outPiece[0] = VillageStartPiece.SAVANNA_MEETING_POINT_1;
                else if (t < 150) outPiece[0] = VillageStartPiece.SAVANNA_MEETING_POINT_2;
                else if (t < 300) outPiece[0] = VillageStartPiece.SAVANNA_MEETING_POINT_3;
                else if (t < 450) outPiece[0] = VillageStartPiece.SAVANNA_MEETING_POINT_4;
                else {
                    outAbandoned[0] = true;
                    if (t < 452) outPiece[0] = VillageStartPiece.SAVANNA_MEETING_POINT_1;
                    else if (t < 453) outPiece[0] = VillageStartPiece.SAVANNA_MEETING_POINT_2;
                    else if (t < 456) outPiece[0] = VillageStartPiece.SAVANNA_MEETING_POINT_3;
                    else outPiece[0] = VillageStartPiece.SAVANNA_MEETING_POINT_4;
                }
                break;

            case TAIGA:
                t = rand.nextInt(100);
                if (t < 49) outPiece[0] = VillageStartPiece.TAIGA_MEETING_POINT_1;
                else if (t < 98) outPiece[0] = VillageStartPiece.TAIGA_MEETING_POINT_2;
                else {
                    outAbandoned[0] = true;
                    if (t < 99) outPiece[0] = VillageStartPiece.TAIGA_MEETING_POINT_1;
                    else outPiece[0] = VillageStartPiece.TAIGA_MEETING_POINT_2;
                }
                break;

            case SNOWY:
                t = rand.nextInt(306);
                if (t < 100) outPiece[0] = VillageStartPiece.SNOWY_MEETING_POINT_1;
                else if (t < 150) outPiece[0] = VillageStartPiece.SNOWY_MEETING_POINT_2;
                else if (t < 300) outPiece[0] = VillageStartPiece.SNOWY_MEETING_POINT_3;
                else {
                    outAbandoned[0] = true;
                    if (t < 302) outPiece[0] = VillageStartPiece.SNOWY_MEETING_POINT_1;
                    else if (t < 303) outPiece[0] = VillageStartPiece.SNOWY_MEETING_POINT_2;
                    else outPiece[0] = VillageStartPiece.SNOWY_MEETING_POINT_3;
                }
                break;
        }
    }

    private static final Map<PortalType, PortalTemplateData> PORTAL_CHEST_DATA = new EnumMap<>(PortalType.class);

    static {
        PORTAL_CHEST_DATA.put(PortalType.GIANT_PORTAL_1, new PortalTemplateData(11, 16, 4, 3));
        PORTAL_CHEST_DATA.put(PortalType.GIANT_PORTAL_2, new PortalTemplateData(11, 16, 9, 9));
        PORTAL_CHEST_DATA.put(PortalType.GIANT_PORTAL_3, new PortalTemplateData(16, 16, 9, 3));

        PORTAL_CHEST_DATA.put(PortalType.PORTAL_1, new PortalTemplateData(6, 6, 2, 0));
        PORTAL_CHEST_DATA.put(PortalType.PORTAL_2, new PortalTemplateData(9, 9, 8, 6));
        PORTAL_CHEST_DATA.put(PortalType.PORTAL_3, new PortalTemplateData(12, 9, 11, 7));
        PORTAL_CHEST_DATA.put(PortalType.PORTAL_4, new PortalTemplateData(12, 9, 11, 3));
        PORTAL_CHEST_DATA.put(PortalType.PORTAL_5, new PortalTemplateData(10, 7, 4, 2));
        PORTAL_CHEST_DATA.put(PortalType.PORTAL_6, new PortalTemplateData(5, 7, 1, 4));
        PORTAL_CHEST_DATA.put(PortalType.PORTAL_7, new PortalTemplateData(9, 9, 0, 2));
        PORTAL_CHEST_DATA.put(PortalType.PORTAL_8, new PortalTemplateData(14, 9, 4, 2));
        PORTAL_CHEST_DATA.put(PortalType.PORTAL_9, new PortalTemplateData(10, 9, 4, 0));
        PORTAL_CHEST_DATA.put(PortalType.PORTAL_10, new PortalTemplateData(12, 10, 2, 7));
    }

    private static int[] applyPortalTransform(PortalTemplateData data, BlockRotation rotation, BlockMirror mirror) {
        int x = data.chestX;
        int z = data.chestZ;

        if (mirror == BlockMirror.FRONT_BACK) {
            x = data.sizeX - x;
        }

        switch (rotation) {
            case NONE:
                return new int[]{x, z};
            case CLOCKWISE_90:
                return new int[]{data.sizeZ - z, x};
            case CLOCKWISE_180:
                return new int[]{data.sizeX - x, data.sizeZ - z};
            case COUNTERCLOCKWISE_90:
                return new int[]{z, data.sizeX - x};
            default:
                return new int[]{x, z};
        }
    }

    private static List<PortalCandidate> findPortalChestCandidates(int localX, int localZ) {
        List<PortalCandidate> candidates = new ArrayList<>();
        for (Map.Entry<PortalType, PortalTemplateData> entry : PORTAL_CHEST_DATA.entrySet()) {
            PortalType type = entry.getKey();
            PortalTemplateData data = entry.getValue();
            for (BlockRotation rotation : BlockRotation.values()) {
                for (BlockMirror mirror : BlockMirror.values()) {
                    int[] transformed = applyPortalTransform(data, rotation, mirror);
                    if (transformed[0] == localX && transformed[1] == localZ) {
                        candidates.add(new PortalCandidate(type, rotation, mirror));
                    }
                }
            }
        }
        return candidates;
    }

    // =======================================================================
    // 6. Full validation functions (CPU versions)
    // =======================================================================

    private static boolean checkVillageFull(long structureSeed, Constraint c, StandaloneChunkRand rand) {
        int regX = floorDiv(c.chunkX, VILLAGE_SPACING);
        int regZ = floorDiv(c.chunkZ, VILLAGE_SPACING);
        rand.setRegionSeed(structureSeed, regX, regZ, VILLAGE_SALT);

        int offset = VILLAGE_SPACING - VILLAGE_SEPARATION;
        if (regX * VILLAGE_SPACING + rand.nextInt(offset) != c.chunkX) return false;
        if (regZ * VILLAGE_SPACING + rand.nextInt(offset) != c.chunkZ) return false;

        rand.setCarverSeed(structureSeed, c.chunkX, c.chunkZ);

        VillageStartPiece[] foundPiece = new VillageStartPiece[1];
        BlockRotation[] foundRot = new BlockRotation[1];
        boolean[] foundAbandoned = new boolean[1];

        getVillageProperties(foundPiece, foundRot, foundAbandoned, c.village.type, rand);

        return foundPiece[0] == c.village.piece &&
                foundRot[0] == c.village.rotation &&
                foundAbandoned[0] == c.village.isAbandoned;
    }

    private static boolean checkShipwreckFull(long structureSeed, Constraint c, StandaloneChunkRand rand) {
        int regX = floorDiv(c.chunkX, SHIPWRECK_SPACING);
        int regZ = floorDiv(c.chunkZ, SHIPWRECK_SPACING);
        rand.setRegionSeed(structureSeed, regX, regZ, SHIPWRECK_SALT);

        int offset = SHIPWRECK_SPACING - SHIPWRECK_SEPARATION;
        if (regX * SHIPWRECK_SPACING + rand.nextInt(offset) != c.chunkX) return false;
        if (regZ * SHIPWRECK_SPACING + rand.nextInt(offset) != c.chunkZ) return false;

        rand.setCarverSeed(structureSeed, c.chunkX, c.chunkZ);

        BlockRotation rot = BlockRotation.values()[rand.nextInt(4)];
        if (rot != c.shipwreck.rotation) return false;

        ShipwreckType type;
        if (c.shipwreck.isBeached) {
            type = STRUCTURE_LOCATION_BEACHED[rand.nextInt(BEACHED_TYPE_COUNT)];
        } else {
            type = STRUCTURE_LOCATION_OCEAN[rand.nextInt(OCEAN_TYPE_COUNT)];
        }
        return type == c.shipwreck.type;
    }

    private static boolean checkPortalFull(long structureSeed, Constraint c, StandaloneChunkRand rand) {
        int regX = floorDiv(c.chunkX, PORTAL_SPACING);
        int regZ = floorDiv(c.chunkZ, PORTAL_SPACING);
        rand.setRegionSeed(structureSeed, regX, regZ, RUINED_PORTAL_SALT);

        int offset = PORTAL_SPACING - PORTAL_SEPARATION;
        int offX = rand.nextInt(offset);
        int genX = regX * PORTAL_SPACING + offX;
        int offZ = rand.nextInt(offset);
        int genZ = regZ * PORTAL_SPACING + offZ;

        if (DEBUG_VERBOSE) {
            System.out.println("  [Portal] reg=(" + regX + "," + regZ + "), offset=(" + offX + "," + offZ + "), gen=(" + genX + "," + genZ + ")");
        }

        if (genX != c.chunkX || genZ != c.chunkZ) return false;

        rand.setCarverSeed(structureSeed, c.chunkX, c.chunkZ);

        if (DEBUG_VERBOSE) {
            System.out.println("  [Portal] category=" + c.portal.category);
        }

        // Category-specific extra floats
        switch (c.portal.category) {
            case DESERT:
                if (DEBUG_VERBOSE) {
                    System.out.println("  [Portal] DESERT branch: no extra float");
                }
                break;
            case JUNGLE: {
                if (DEBUG_VERBOSE) {
                    System.out.println("  [Portal] JUNGLE branch: consuming 1 float");
                }
                float f = rand.nextFloat();
                if (DEBUG_VERBOSE) {
                    System.out.println("    consumed=" + f);
                }
                break;
            }
            case MOUNTAINS: {
                float f1 = rand.nextFloat();
                if (DEBUG_VERBOSE) {
                    System.out.println("  [Portal] MOUNTAINS branch: f1=" + f1);
                }
                if (f1 >= 0.5f) {
                    float f2 = rand.nextFloat();
                    if (DEBUG_VERBOSE) {
                        System.out.println("  [Portal] MOUNTAINS branch: f2=" + f2);
                    }
                }
                break;
            }
        }

        float giantRoll = rand.nextFloat();
        boolean giant = (giantRoll < 0.05f);
        PortalType t;
        if (giant) {
            int gi = rand.nextInt(3);
            t = PortalType.values()[PortalType.GIANT_PORTAL_1.ordinal() + gi];
        } else {
            int ni = rand.nextInt(10);
            t = PortalType.values()[PortalType.PORTAL_1.ordinal() + ni];
        }

        BlockRotation rot = BlockRotation.values()[rand.nextInt(4)];
        float mirrorRoll = rand.nextFloat();
        BlockMirror mirror = (mirrorRoll < 0.5f) ? BlockMirror.NONE : BlockMirror.FRONT_BACK;

        if (DEBUG_VERBOSE) {
            System.out.println("  [Portal] giantRoll=" + giantRoll + " → " + (giant ? "GIANT" : "NORMAL"));
            System.out.println("  [Portal] type=" + t + " (expected " + c.portal.type + ")");
            System.out.println("  [Portal] rotation=" + rot + " (expected " + c.portal.rotation + ")");
            System.out.println("  [Portal] mirror=" + mirror + " (expected " + c.portal.mirror + ")");
        }

        if (t != c.portal.type) return false;
        if (rot != c.portal.rotation) return false;
        if (mirror != c.portal.mirror) return false;

        return true;
    }

    private static boolean checkPortalChest(long structureSeed, Constraint c, StandaloneChunkRand rand) {
        int regX = floorDiv(c.chunkX, PORTAL_SPACING);
        int regZ = floorDiv(c.chunkZ, PORTAL_SPACING);
        rand.setRegionSeed(structureSeed, regX, regZ, RUINED_PORTAL_SALT);

        int offset = PORTAL_SPACING - PORTAL_SEPARATION;
        int offX = rand.nextInt(offset);
        int genX = regX * PORTAL_SPACING + offX;
        int offZ = rand.nextInt(offset);
        int genZ = regZ * PORTAL_SPACING + offZ;

        if (DEBUG_VERBOSE) {
            System.out.println("  [Portal] reg=(" + regX + "," + regZ + "), offset=(" + offX + "," + offZ + "), gen=(" + genX + "," + genZ + ")");
        }

        if (genX != c.chunkX || genZ != c.chunkZ) return false;

        rand.setCarverSeed(structureSeed, c.chunkX, c.chunkZ);

        if (DEBUG_VERBOSE) {
            System.out.println("  [Portal] category=" + c.portalChest.category);
        }

        switch (c.portalChest.category) {
            case DESERT:
                if (DEBUG_VERBOSE) {
                    System.out.println("  [Portal] DESERT branch: no extra float");
                }
                break;
            case JUNGLE: {
                if (DEBUG_VERBOSE) {
                    System.out.println("  [Portal] JUNGLE branch: consuming 1 float");
                }
                float f = rand.nextFloat();
                if (DEBUG_VERBOSE) {
                    System.out.println("    consumed=" + f);
                }
                break;
            }
            case MOUNTAINS: {
                float f1 = rand.nextFloat();
                if (DEBUG_VERBOSE) {
                    System.out.println("  [Portal] MOUNTAINS branch: f1=" + f1);
                }
                if (f1 >= 0.5f) {
                    float f2 = rand.nextFloat();
                    if (DEBUG_VERBOSE) {
                        System.out.println("  [Portal] MOUNTAINS branch: f2=" + f2);
                    }
                }
                break;
            }
        }

        float giantRoll = rand.nextFloat();
        boolean giant = (giantRoll < 0.05f);
        PortalType t;
        if (giant) {
            int gi = rand.nextInt(3);
            t = PortalType.values()[PortalType.GIANT_PORTAL_1.ordinal() + gi];
        } else {
            int ni = rand.nextInt(10);
            t = PortalType.values()[PortalType.PORTAL_1.ordinal() + ni];
        }

        BlockRotation rot = BlockRotation.values()[rand.nextInt(4)];
        float mirrorRoll = rand.nextFloat();
        BlockMirror mirror = (mirrorRoll < 0.5f) ? BlockMirror.NONE : BlockMirror.FRONT_BACK;

        if (DEBUG_VERBOSE) {
            System.out.println("  [Portal] giantRoll=" + giantRoll + " → " + (giant ? "GIANT" : "NORMAL"));
            System.out.println("  [Portal] type=" + t);
            System.out.println("  [Portal] rotation=" + rot);
            System.out.println("  [Portal] mirror=" + mirror);
            System.out.println("  [Portal] candidateCount=" + c.portalChest.candidates.size());
        }

        for (PortalCandidate candidate : c.portalChest.candidates) {
            if (candidate.type == t && candidate.rotation == rot && candidate.mirror == mirror) {
                return true;
            }
        }

        return false;
    }

    private static boolean checkConstraint(long structureSeed, Constraint c, StandaloneChunkRand rand) {
        if (c.type == ConstraintType.RUINED_PORTAL) {
            return checkPortalFull(structureSeed, c, rand);
        }
        if (c.type == ConstraintType.PORTAL_CHEST) {
            return checkPortalChest(structureSeed, c, rand);
        }
        if (c.type == ConstraintType.SHIPWRECK) {
            return checkShipwreckFull(structureSeed, c, rand);
        }
        return checkVillageFull(structureSeed, c, rand);
    }

    // =======================================================================
    // 7. Shipwreck 20-bit fast filter (must use 32-bit state)
    // =======================================================================

    private static boolean canGenerateShipwreck20BitFastFilter(int lower20bits, int chunkX, int chunkZ) {
        int regX = floorDiv(chunkX, SHIPWRECK_SPACING);
        int regZ = floorDiv(chunkZ, SHIPWRECK_SPACING);

        long regionalSeed32 = ((long) lower20bits
                + (long) regX * MULT_A
                + (long) regZ * MULT_B
                + SHIPWRECK_SALT) ^ XOR_MASK;

        // 32-bit truncation like uint32_t
        regionalSeed32 &= 0xffffffffL;

        regionalSeed32 = (regionalSeed32 * LCG_MULT + LCG_ADD) & 0xffffffffL;
        int xCheck = (int) ((regionalSeed32 >>> 17) & 3);

        regionalSeed32 = (regionalSeed32 * LCG_MULT + LCG_ADD) & 0xffffffffL;
        int zCheck = (int) ((regionalSeed32 >>> 17) & 3);

        return xCheck == (chunkX & 3) && zCheck == (chunkZ & 3);
    }

    // =======================================================================
    // 8. Structure abstraction & registry
    // =======================================================================

    interface IStructure {
        String getName();
        ConstraintType getType();
        boolean tryParseConstraint(List<String> parts, Constraint out);
        default void initializeDeviceConstants() {}
        default boolean hasFastFilter() { return false; }
        default boolean hasReversingKernel() { return false; }
    }

    static class VillageStructure implements IStructure {
        private final Map<String, VillageStartPiece> nameToPiece = new HashMap<>();
        private final Map<Integer, VillageType> biomeIdToType = new HashMap<>();

        VillageStructure() {
            biomeIdToType.put(1, VillageType.PLAINS);
            biomeIdToType.put(2, VillageType.SNOWY);
            biomeIdToType.put(3, VillageType.TAIGA);
            biomeIdToType.put(4, VillageType.SAVANNA);
            biomeIdToType.put(5, VillageType.DESERT);

            nameToPiece.put("plains_fountain_01", VillageStartPiece.PLAINS_FOUNTAIN_01);
            nameToPiece.put("plains_meeting_point_1", VillageStartPiece.PLAINS_MEETING_POINT_1);
            nameToPiece.put("plains_meeting_point_2", VillageStartPiece.PLAINS_MEETING_POINT_2);
            nameToPiece.put("plains_meeting_point_3", VillageStartPiece.PLAINS_MEETING_POINT_3);

            nameToPiece.put("desert_meeting_point_1", VillageStartPiece.DESERT_MEETING_POINT_1);
            nameToPiece.put("desert_meeting_point_2", VillageStartPiece.DESERT_MEETING_POINT_2);
            nameToPiece.put("desert_meeting_point_3", VillageStartPiece.DESERT_MEETING_POINT_3);

            nameToPiece.put("savanna_meeting_point_1", VillageStartPiece.SAVANNA_MEETING_POINT_1);
            nameToPiece.put("savanna_meeting_point_2", VillageStartPiece.SAVANNA_MEETING_POINT_2);
            nameToPiece.put("savanna_meeting_point_3", VillageStartPiece.SAVANNA_MEETING_POINT_3);
            nameToPiece.put("savanna_meeting_point_4", VillageStartPiece.SAVANNA_MEETING_POINT_4);

            nameToPiece.put("taiga_meeting_point_1", VillageStartPiece.TAIGA_MEETING_POINT_1);
            nameToPiece.put("taiga_meeting_point_2", VillageStartPiece.TAIGA_MEETING_POINT_2);

            nameToPiece.put("snowy_meeting_point_1", VillageStartPiece.SNOWY_MEETING_POINT_1);
            nameToPiece.put("snowy_meeting_point_2", VillageStartPiece.SNOWY_MEETING_POINT_2);
            nameToPiece.put("snowy_meeting_point_3", VillageStartPiece.SNOWY_MEETING_POINT_3);
        }

        @Override
        public String getName() {
            return "Village";
        }

        @Override
        public ConstraintType getType() {
            return ConstraintType.VILLAGE;
        }

        @Override
        public boolean hasFastFilter() {
            return false;
        }

        @Override
        public boolean hasReversingKernel() {
            return false;
        }

        @Override
        public boolean tryParseConstraint(List<String> parts, Constraint c) {
            // Format: ChunkX, ChunkZ, ROTATION, piece_name, biome_id, [is_abandoned]
            if (parts.size() < 5 || parts.size() > 6) return false;

            String pieceName = parts.get(3);
            if (!nameToPiece.containsKey(pieceName)) return false;

            int biomeId;
            try {
                biomeId = Integer.parseInt(parts.get(4));
            } catch (NumberFormatException e) {
                return false;
            }
            if (!biomeIdToType.containsKey(biomeId)) return false;

            c.type = getType();
            c.village.piece = nameToPiece.get(pieceName);
            c.village.type = biomeIdToType.get(biomeId);

            c.village.isAbandoned = false;
            if (parts.size() == 6) {
                String s = parts.get(5).trim().toLowerCase();
                if (s.equals("yes")) c.village.isAbandoned = true;
                else if (!s.equals("no")) return false;
            }
            return true;
        }
    }

    static class ShipwreckStructure implements IStructure {
        private final Map<String, ShipwreckType> nameToType = new HashMap<>();

        ShipwreckStructure() {
            nameToType.put("rightsideup_backhalf", ShipwreckType.RIGHTSIDEUP_BACKHALF);
            nameToType.put("rightsideup_backhalf_degraded", ShipwreckType.RIGHTSIDEUP_BACKHALF_DEGRADED);
            nameToType.put("rightsideup_fronthalf", ShipwreckType.RIGHTSIDEUP_FRONTHALF);
            nameToType.put("rightsideup_fronthalf_degraded", ShipwreckType.RIGHTSIDEUP_FRONTHALF_DEGRADED);
            nameToType.put("rightsideup_full", ShipwreckType.RIGHTSIDEUP_FULL);
            nameToType.put("rightsideup_full_degraded", ShipwreckType.RIGHTSIDEUP_FULL_DEGRADED);

            nameToType.put("sideways_backhalf", ShipwreckType.SIDEWAYS_BACKHALF);
            nameToType.put("sideways_backhalf_degraded", ShipwreckType.SIDEWAYS_BACKHALF_DEGRADED);
            nameToType.put("sideways_fronthalf", ShipwreckType.SIDEWAYS_FRONTHALF);
            nameToType.put("sideways_fronthalf_degraded", ShipwreckType.SIDEWAYS_FRONTHALF_DEGRADED);
            nameToType.put("sideways_full", ShipwreckType.SIDEWAYS_FULL);
            nameToType.put("sideways_full_degraded", ShipwreckType.SIDEWAYS_FULL_DEGRADED);

            nameToType.put("upsidedown_backhalf", ShipwreckType.UPSIDEDOWN_BACKHALF);
            nameToType.put("upsidedown_backhalf_degraded", ShipwreckType.UPSIDEDOWN_BACKHALF_DEGRADED);
            nameToType.put("upsidedown_fronthalf", ShipwreckType.UPSIDEDOWN_FRONTHALF);
            nameToType.put("upsidedown_fronthalf_degraded", ShipwreckType.UPSIDEDOWN_FRONTHALF_DEGRADED);
            nameToType.put("upsidedown_full", ShipwreckType.UPSIDEDOWN_FULL);
            nameToType.put("upsidedown_full_degraded", ShipwreckType.UPSIDEDOWN_FULL_DEGRADED);

            nameToType.put("with_mast", ShipwreckType.WITH_MAST);
            nameToType.put("with_mast_degraded", ShipwreckType.WITH_MAST_DEGRADED);
        }

        @Override
        public String getName() {
            return "Shipwreck";
        }

        @Override
        public ConstraintType getType() {
            return ConstraintType.SHIPWRECK;
        }

        @Override
        public boolean hasFastFilter() {
            return true;
        }

        @Override
        public boolean hasReversingKernel() {
            return true;
        }

        @Override
        public void initializeDeviceConstants() {
            ShipwreckType[] oceanTypes = new ShipwreckType[]{
                    nameToType.get("with_mast"),
                    nameToType.get("upsidedown_full"),
                    nameToType.get("upsidedown_fronthalf"),
                    nameToType.get("upsidedown_backhalf"),
                    nameToType.get("sideways_full"),
                    nameToType.get("sideways_fronthalf"),
                    nameToType.get("sideways_backhalf"),
                    nameToType.get("rightsideup_full"),
                    nameToType.get("rightsideup_fronthalf"),
                    nameToType.get("rightsideup_backhalf"),
                    nameToType.get("with_mast_degraded"),
                    nameToType.get("upsidedown_full_degraded"),
                    nameToType.get("upsidedown_fronthalf_degraded"),
                    nameToType.get("upsidedown_backhalf_degraded"),
                    nameToType.get("sideways_full_degraded"),
                    nameToType.get("sideways_fronthalf_degraded"),
                    nameToType.get("sideways_backhalf_degraded"),
                    nameToType.get("rightsideup_full_degraded"),
                    nameToType.get("rightsideup_fronthalf_degraded"),
                    nameToType.get("rightsideup_backhalf_degraded")
            };
            System.arraycopy(oceanTypes, 0, STRUCTURE_LOCATION_OCEAN, 0, OCEAN_TYPE_COUNT);

            ShipwreckType[] beachedTypes = new ShipwreckType[]{
                    nameToType.get("with_mast"),
                    nameToType.get("sideways_full"),
                    nameToType.get("sideways_fronthalf"),
                    nameToType.get("sideways_backhalf"),
                    nameToType.get("rightsideup_full"),
                    nameToType.get("rightsideup_fronthalf"),
                    nameToType.get("rightsideup_backhalf"),
                    nameToType.get("with_mast_degraded"),
                    nameToType.get("rightsideup_full_degraded"),
                    nameToType.get("rightsideup_fronthalf_degraded"),
                    nameToType.get("rightsideup_backhalf_degraded")
            };
            System.arraycopy(beachedTypes, 0, STRUCTURE_LOCATION_BEACHED, 0, BEACHED_TYPE_COUNT);
        }

        @Override
        public boolean tryParseConstraint(List<String> parts, Constraint c) {
            if (parts.size() != 5) return false;
            String typeName = parts.get(3);
            if (!nameToType.containsKey(typeName)) return false;

            c.type = getType();
            c.shipwreck.type = nameToType.get(typeName);

            String biome = parts.get(4).trim().toLowerCase();
            if (biome.equals("beached")) c.shipwreck.isBeached = true;
            else if (biome.equals("ocean")) c.shipwreck.isBeached = false;
            else return false;

            return true;
        }
    }

    static class RuinedPortalStructure implements IStructure {
        private final Map<String, PortalType> nameToType = new HashMap<>();

        RuinedPortalStructure() {
            nameToType.put("portal_1", PortalType.PORTAL_1);
            nameToType.put("portal_2", PortalType.PORTAL_2);
            nameToType.put("portal_3", PortalType.PORTAL_3);
            nameToType.put("portal_4", PortalType.PORTAL_4);
            nameToType.put("portal_5", PortalType.PORTAL_5);
            nameToType.put("portal_6", PortalType.PORTAL_6);
            nameToType.put("portal_7", PortalType.PORTAL_7);
            nameToType.put("portal_8", PortalType.PORTAL_8);
            nameToType.put("portal_9", PortalType.PORTAL_9);
            nameToType.put("portal_10", PortalType.PORTAL_10);

            nameToType.put("giant_portal_1", PortalType.GIANT_PORTAL_1);
            nameToType.put("giant_portal_2", PortalType.GIANT_PORTAL_2);
            nameToType.put("giant_portal_3", PortalType.GIANT_PORTAL_3);
        }

        @Override
        public String getName() {
            return "Ruined Portal";
        }

        @Override
        public ConstraintType getType() {
            return ConstraintType.RUINED_PORTAL;
        }

        @Override
        public boolean hasReversingKernel() {
            return true;
        }

        @Override
        public boolean tryParseConstraint(List<String> parts, Constraint c) {
            if (parts.size() != 6) return false;
            String typeName = parts.get(3);
            if (!nameToType.containsKey(typeName)) return false;

            c.type = getType();
            c.portal.type = nameToType.get(typeName);

            String mirror = parts.get(4).trim().toLowerCase();
            if (mirror.equals("yes")) c.portal.mirror = BlockMirror.FRONT_BACK;
            else if (mirror.equals("no")) c.portal.mirror = BlockMirror.NONE;
            else return false;

            int cat;
            try {
                cat = Integer.parseInt(parts.get(5));
            } catch (NumberFormatException e) {
                return false;
            }
            c.portal.category = BiomeCategory.fromInt(cat);

            return true;
        }
    }

    static class StructureRegistry {
        private final List<IStructure> structures = new ArrayList<>();
        private final Map<String, BlockRotation> nameToRot = new HashMap<>();
        private final String portalChestPrefix = "PORTALCHEST";

        StructureRegistry() {
            structures.add(new ShipwreckStructure());
            structures.add(new RuinedPortalStructure());
            structures.add(new VillageStructure());

            nameToRot.put("NONE", BlockRotation.NONE);
            nameToRot.put("CLOCKWISE_90", BlockRotation.CLOCKWISE_90);
            nameToRot.put("CLOCKWISE_180", BlockRotation.CLOCKWISE_180);
            nameToRot.put("COUNTERCLOCKWISE_90", BlockRotation.COUNTERCLOCKWISE_90);
        }

        void initializeAllDeviceConstants() {
            for (IStructure s : structures) {
                s.initializeDeviceConstants();
            }
        }

        boolean parseLine(String line, Constraint out) {
            String trimmed = line.trim();
            if (trimmed.toUpperCase().startsWith(portalChestPrefix)) {
                String remainder = trimmed.substring(portalChestPrefix.length()).trim();
                if (remainder.startsWith(":") || remainder.startsWith(",")) {
                    remainder = remainder.substring(1).trim();
                }
                if (remainder.isEmpty()) return false;
                String[] tokens = remainder.split("[,\\s]+");
                if (tokens.length != 3) return false;
                try {
                    int worldX = Integer.parseInt(tokens[0]);
                    int worldZ = Integer.parseInt(tokens[1]);
                    int cat = Integer.parseInt(tokens[2]);
                    BiomeCategory category = BiomeCategory.fromInt(cat);
                    int chunkX = floorDiv(worldX, 16);
                    int chunkZ = floorDiv(worldZ, 16);
                    int localX = worldX - chunkX * 16;
                    int localZ = worldZ - chunkZ * 16;
                    List<PortalCandidate> candidates = findPortalChestCandidates(localX, localZ);
                    if (candidates.isEmpty()) return false;

                    out.type = ConstraintType.PORTAL_CHEST;
                    out.chunkX = chunkX;
                    out.chunkZ = chunkZ;
                    out.portalChest.category = category;
                    out.portalChest.candidates = candidates;
                    return true;
                } catch (NumberFormatException e) {
                    return false;
                }
            }

            String[] tokens = line.split(",");
            List<String> parts = new ArrayList<>();
            for (String t : tokens) parts.add(t.trim());
            if (parts.size() < 4) return false;

            try {
                Constraint c = new Constraint();
                c.chunkX = Integer.parseInt(parts.get(0));
                c.chunkZ = Integer.parseInt(parts.get(1));

                if (!nameToRot.containsKey(parts.get(2))) return false;
                BlockRotation rot = nameToRot.get(parts.get(2));

                for (IStructure s : structures) {
                    if (s.tryParseConstraint(parts, c)) {
                        if (c.type == ConstraintType.RUINED_PORTAL) c.portal.rotation = rot;
                        else if (c.type == ConstraintType.SHIPWRECK) c.shipwreck.rotation = rot;
                        else if (c.type == ConstraintType.VILLAGE) c.village.rotation = rot;

                        out.type = c.type;
                        out.chunkX = c.chunkX;
                        out.chunkZ = c.chunkZ;
                        out.shipwreck = c.shipwreck;
                        out.portal = c.portal;
                        out.village = c.village;
                        return true;
                    }
                }
            } catch (Exception e) {
                return false;
            }
            return false;
        }
    }

    // =======================================================================
    // 9. Progress bar helper
    // =======================================================================

    private static long lastPrintTimeNs = 0;

    private static void progressBar(long current, long total, long startTimeNs) {
        if (total <= 0) return;
        long now = System.nanoTime();

        // Throttle to ~5 updates per second
        if (now - lastPrintTimeNs < 200_000_000L) {
            return;
        }
        lastPrintTimeNs = now;

        if (current < 0) current = 0;
        if (current > total) current = total;

        double pct = (double) current / (double) total;
        long elapsedNs = now - startTimeNs;
        double secs = elapsedNs / 1e9;
        if (secs <= 0) secs = 1e-9;
        double rate = current / secs;
        if (rate <= 0) rate = 1e-9;
        double remain = (total - current) / rate;

        int barWidth = 30;
        int filled = (int) Math.round(pct * barWidth);
        if (filled > barWidth) filled = barWidth;

        StringBuilder bar = new StringBuilder();
        bar.append('[');
        for (int i = 0; i < barWidth; i++) {
            bar.append(i < filled ? '=' : ' ');
        }
        bar.append(']');

        System.out.printf(
                "\r%s %d/%d (%.2f%%) | %.1fs elapsed | %.1fk/s | ETA %.1fs",
                bar.toString(), current, total, pct * 100.0,
                secs, rate / 1000.0, remain
        );
        System.out.flush();
    }

    // =======================================================================
    // 10. Search strategies
    // =======================================================================

    interface SearchStrategy {
        void execute(List<Long> results);
        String getDescription();
    }

    static class PillarseedSearch implements SearchStrategy {
        private final long pillarSeed32;
        private final List<Constraint> constraints;

        PillarseedSearch(long seed, List<Constraint> constraints) {
            if (constraints.isEmpty()) {
                throw new IllegalArgumentException("Pillarseed mode requires at least one constraint.");
            }
            this.pillarSeed32 = seed & 0xffffffffL;
            this.constraints = constraints;
        }

        @Override
        public String getDescription() {
            return "--- Pillarseed Mode Activated ---\n" +
                    "Using Pillarseed: " + pillarSeed32 + "\n" +
                    "With " + constraints.size() + " constraint(s).\n\n" +
                    "WARNING: CPU implementation brute-forces 2^32 seeds.\n";
        }

        @Override
        public void execute(List<Long> results) {
            StandaloneChunkRand rand = new StandaloneChunkRand();

            long total = 1L << 32;
            long counter = 0;
            long startNs = System.nanoTime();

            for (int lower16 = 0; lower16 < 65536; lower16++) {
                long partialState = (pillarSeed32 << 16) | (lower16 & 0xffffL);
                long state1 = partialState * PILLAR_MULT + PILLAR_ADD;
                long state2 = state1 * PILLAR_MULT + PILLAR_ADD;
                long halfSeed32 = (state2 ^ XOR_MASK) & 0xffffffffL;

                for (int upper16 = 0; upper16 < 65536; upper16++) {
                    long candidateSeed = ((long) upper16 << 32) | halfSeed32;

                    boolean okAll = true;
                    for (Constraint c : constraints) {
                    boolean ok;
                    ok = checkConstraint(candidateSeed, c, rand);
                    if (!ok) {
                        okAll = false;
                        break;
                    }
                }
                if (okAll) {
                        results.add(candidateSeed & MASK_48);
                    }

                    counter++;
                    progressBar(counter, total, startNs);
                }
            }
            System.out.println();
        }
    }

    static class StandardSearch implements SearchStrategy {
        private final List<Constraint> allConstraints;
        private int[] validLower20Bits;
        private int lower20Count;

        StandardSearch(List<Constraint> constraints) {
            this.allConstraints = constraints;
        }

        @Override
        public String getDescription() {
            return "--- Standard Structure Seed Search (CPU) ---";
        }

        private void runStage1() {
            System.out.println("\n--- Stage 1: Filtering Lower 20-bit seed patterns ---");
            List<Constraint> shipwreckConstraints = allConstraints.stream()
                    .filter(c -> c.type == ConstraintType.SHIPWRECK)
                    .collect(Collectors.toList());

            boolean hasFastFilterable = !shipwreckConstraints.isEmpty();

            if (hasFastFilterable) {
                System.out.println("Using " + shipwreckConstraints.size() +
                        " shipwreck(s) to filter 20-bit candidates...");
                List<Integer> candidates = new ArrayList<>();
                final int limit = 1 << 20; // 1,048,576
                long startNs = System.nanoTime();

                for (int lower20 = 0; lower20 < limit; lower20++) {
                    boolean okAll = true;
                    for (Constraint c : shipwreckConstraints) {
                        if (!canGenerateShipwreck20BitFastFilter(lower20, c.chunkX, c.chunkZ)) {
                            okAll = false;
                            break;
                        }
                    }
                    if (okAll) candidates.add(lower20);
                    progressBar(lower20 + 1L, limit, startNs);
                }
                System.out.println();

                lower20Count = candidates.size();
                validLower20Bits = candidates.stream().mapToInt(i -> i).toArray();
            } else {
                System.out.println("No fast-filterable constraints. Using all 2^20 candidates.");
                lower20Count = 1 << 20;
                validLower20Bits = new int[lower20Count];

                long startNs = System.nanoTime();
                for (int i = 0; i < lower20Count; i++) {
                    validLower20Bits[i] = i;
                    progressBar(i + 1L, lower20Count, startNs);
                }
                System.out.println();
            }

            System.out.println("Found " + lower20Count + " potential 20-bit candidates.");
        }

        private boolean validateCandidate(long seed, List<Constraint> validators, StandaloneChunkRand rand) {
            for (Constraint c : validators) {
                boolean ok = checkConstraint(seed, c, rand);
                if (!ok) return false;
            }
            return true;
        }

        private void runStage2Reversing(List<Long> results) {
            System.out.println("\n--- Stage 2: Using REVERSING Approach ---");

            int anchorIdx = -1;
            for (int i = 0; i < allConstraints.size(); i++) {
                if (allConstraints.get(i).type == ConstraintType.RUINED_PORTAL ||
                        allConstraints.get(i).type == ConstraintType.PORTAL_CHEST) {
                    anchorIdx = i;
                    break;
                }
            }
            if (anchorIdx == -1) {
                for (int i = 0; i < allConstraints.size(); i++) {
                    if (allConstraints.get(i).type == ConstraintType.SHIPWRECK) {
                        anchorIdx = i;
                        break;
                    }
                }
            }
            if (anchorIdx == -1) {
                System.out.println("No reversible anchor found; falling back to bruteforce.");
                runStage2Bruteforce(results);
                return;
            }

            Constraint anchor = allConstraints.get(anchorIdx);
            List<Constraint> validators = new ArrayList<>();
            for (int i = 0; i < allConstraints.size(); i++) {
                if (i != anchorIdx) validators.add(allConstraints.get(i));
            }

            String anchorTypeStr = (anchor.type == ConstraintType.SHIPWRECK) ? "Shipwreck" : "Portal";
            System.out.println("Using " + anchorTypeStr + " at [" + anchor.chunkX + "," + anchor.chunkZ + "] as anchor.");

            StandaloneChunkRand rand = new StandaloneChunkRand();
            long startNs = System.nanoTime();

            if (anchor.type == ConstraintType.RUINED_PORTAL || anchor.type == ConstraintType.PORTAL_CHEST) {
                int regX = floorDiv(anchor.chunkX, PORTAL_SPACING);
                int regZ = floorDiv(anchor.chunkZ, PORTAL_SPACING);
                int genRegionSize = PORTAL_SPACING - PORTAL_SEPARATION;
                int expectedRelX = ((anchor.chunkX % PORTAL_SPACING) + PORTAL_SPACING) % PORTAL_SPACING;
                int expectedRelZ = ((anchor.chunkZ % PORTAL_SPACING) + PORTAL_SPACING) % PORTAL_SPACING;

                long termX = (long) regX * MULT_A;
                long termZ = (long) regZ * MULT_B;

                for (int i = 0; i < lower20Count; i++) {
                    int lower20 = validLower20Bits[i];

                    long uInitialPart = (lower20 & 0xfffffL) + termX + termZ + RUINED_PORTAL_SALT;
                    long uState0 = (uInitialPart ^ XOR_MASK) & MASK_48;
                    long uState1 = (uState0 * LCG_MULT + LCG_ADD) & MASK_48;
                    long uState2 = (uState1 * LCG_MULT + LCG_ADD) & MASK_48;

                    int lower20OfState2 = (int) (uState2 & 0xfffffL);
                    int K = (lower20OfState2 >> 17);
                    int R = expectedRelZ;
                    int iBase = (K - R % 8 + 8) % 8;
                    int BBase = 25 * iBase + R;

                    for (int m = 0; ; m++) {
                        long bitsZ64 = 200L * m + BBase;
                        if (bitsZ64 >= (1L << 31)) break;
                        int bitsZ = (int) bitsZ64;

                        if (bitsZ - (bitsZ % genRegionSize) + (genRegionSize - 1) >= 0) {
                            long state2Candidate = ((long) bitsZ << 17) | (lower20OfState2 & 0x1ffffL);
                            long state1Candidate = ((state2Candidate - LCG_ADD) * LCG_MULT_INV) & MASK_48;
                            int bitsX = (int) (state1Candidate >>> 17);

                            if (bitsX >= 0 &&
                                    bitsX % genRegionSize == expectedRelX &&
                                    (bitsX - (bitsX % genRegionSize) + (genRegionSize - 1) >= 0)) {

                                long state0Candidate = ((state1Candidate - LCG_ADD) * LCG_MULT_INV) & MASK_48;
                                long scrambled = state0Candidate ^ XOR_MASK;
                                long seed = (scrambled - termX - termZ - RUINED_PORTAL_SALT) & MASK_48;

                                if (checkConstraint(seed, anchor, rand) && validateCandidate(seed, validators, rand)) {
                                    results.add(seed);
                                }
                            }
                        }
                    }

                    progressBar(i + 1L, lower20Count, startNs);
                }
                System.out.println();
            } else { // Shipwreck anchor
                int regX = floorDiv(anchor.chunkX, SHIPWRECK_SPACING);
                int regZ = floorDiv(anchor.chunkZ, SHIPWRECK_SPACING);
                int genRegionSize = SHIPWRECK_SPACING - SHIPWRECK_SEPARATION;
                int expectedRelX = ((anchor.chunkX % SHIPWRECK_SPACING) + SHIPWRECK_SPACING) % SHIPWRECK_SPACING;
                int expectedRelZ = ((anchor.chunkZ % SHIPWRECK_SPACING) + SHIPWRECK_SPACING) % SHIPWRECK_SPACING;

                long termX = (long) regX * MULT_A;
                long termZ = (long) regZ * MULT_B;

                for (int i = 0; i < lower20Count; i++) {
                    int lower20 = validLower20Bits[i];

                    long uInitialPart = (lower20 & 0xfffffL) + termX + termZ + SHIPWRECK_SALT;
                    long uState0 = (uInitialPart ^ XOR_MASK) & MASK_48;
                    long uState1 = (uState0 * LCG_MULT + LCG_ADD) & MASK_48;
                    long uState2 = (uState1 * LCG_MULT + LCG_ADD) & MASK_48;

                    int finalLower20LCG = (int) (uState2 & 0xfffffL);
                    int baseZContrib = finalLower20LCG >> 17;

                    for (int testU = 0; testU < 5; testU++) {
                        if ((((testU << 3) + baseZContrib) % genRegionSize) == expectedRelZ) {
                            for (long j = 0; ; j++) {
                                long upper28LCG = 5L * j + testU;
                                if (upper28LCG >= (1L << 28)) break;

                                long uLcgStateForZ = (upper28LCG << 20) | (finalLower20LCG & 0xfffffL);
                                long uLcgStateForX = ((uLcgStateForZ - LCG_ADD) * LCG_MULT_INV) & MASK_48;

                                if (((uLcgStateForX >>> 17) % genRegionSize) == expectedRelX) {
                                    long uLcgInitial = ((uLcgStateForX - LCG_ADD) * LCG_MULT_INV) & MASK_48;
                                    long scrambled = uLcgInitial ^ XOR_MASK;
                                    long seed = (scrambled - termX - termZ - SHIPWRECK_SALT) & MASK_48;

                                    if (checkConstraint(seed, anchor, rand) && validateCandidate(seed, validators, rand)) {
                                        results.add(seed);
                                    }
                                }
                            }
                        }
                    }

                    progressBar(i + 1L, lower20Count, startNs);
                }
                System.out.println();
            }
        }

        private void runStage2Bruteforce(List<Long> results) {
            System.out.println("\n--- Stage 2: Using BRUTE-FORCE Approach (" +
                    allConstraints.size() + " constraints) ---");
            StandaloneChunkRand rand = new StandaloneChunkRand();

            final long numUpperBits = 1L << 28;
            long totalTasks = (long) lower20Count * numUpperBits;
            System.out.println("Total candidates: " + totalTasks);

            long startNs = System.nanoTime();

            for (long globalIdx = 0; globalIdx < totalTasks; globalIdx++) {
                int lower20Idx = (int) (globalIdx / numUpperBits);
                long upper28 = globalIdx % numUpperBits;

                int lower20Val = validLower20Bits[lower20Idx];
                long candidateSeed = ((upper28 << 20) | (lower20Val & 0xfffffL)) & MASK_48;

                boolean validAll = true;
                for (Constraint c : allConstraints) {
                    boolean ok = checkConstraint(candidateSeed, c, rand);
                    if (!ok) {
                        validAll = false;
                        break;
                    }
                }
                if (validAll) results.add(candidateSeed);

                progressBar(globalIdx + 1L, totalTasks, startNs);
            }
            System.out.println();
        }

        @Override
        public void execute(List<Long> results) {
            runStage1();
            if (lower20Count == 0) {
                System.out.println("No seed candidates found in Stage 1. Exiting.");
                return;
            }

            boolean hasReversibleAnchor = false;
            for (Constraint c : allConstraints) {
                if (c.type == ConstraintType.RUINED_PORTAL ||
                        c.type == ConstraintType.PORTAL_CHEST ||
                        c.type == ConstraintType.SHIPWRECK) {
                    hasReversibleAnchor = true;
                    break;
                }
            }

            boolean useReversing = hasReversibleAnchor &&
                    allConstraints.size() >= 1 &&
                    allConstraints.size() <= 10;

            if (useReversing) {
                runStage2Reversing(results);
            } else {
                runStage2Bruteforce(results);
            }
        }
    }

    // =======================================================================
    // 11. File loading + usage
    // =======================================================================

    static class LoadResult {
        final List<Constraint> constraints;
        final long pillarSeed; // -1 if none

        LoadResult(List<Constraint> constraints, long pillarSeed) {
            this.constraints = constraints;
            this.pillarSeed = pillarSeed;
        }
    }

    private static void printUsage() {
        System.err.println("Usage:");
        System.err.println("  Search mode: java StructureSeedCracker <constraints_file.txt>");
        System.err.println("  Debug mode : java StructureSeedCracker <structure_seed>");
        System.err.println();
        System.err.println("Constraints file format (one per line, '#' for comments):");
        System.err.println("  Shipwreck: ChunkX, ChunkZ, ROTATION, type_name, Ocean|Beached");
        System.err.println("  Portal   : ChunkX, ChunkZ, ROTATION, portal_type, yes|no, biome_category(1-3)");
        System.err.println("  PortalChest: PORTALCHEST: WorldX WorldZ biome_category(1-3)");
        System.err.println("  Village  : ChunkX, ChunkZ, ROTATION, piece_name, biome_id, [yes|no]");
        System.err.println("             biome_id: 1=Plains, 2=Snowy, 3=Taiga, 4=Savanna, 5=Desert");
        System.err.println("Pillarseed Mode:");
        System.err.println("  Add the 32-bit pillarseed as the final number on its own line in the file.");
        System.err.println();
        System.err.println("Debug mode uses constraints from 'constraints.txt' in the current directory.");
    }

    private static LoadResult loadConstraintsFromFile(String filename, StructureRegistry registry) {
        List<String> lines = new ArrayList<>();

        try (BufferedReader br = new BufferedReader(new FileReader(filename))) {
            String raw;
            while ((raw = br.readLine()) != null) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                lines.add(line);
            }
        } catch (IOException e) {
            System.err.println("Error: Could not open file '" + filename + "'");
            return new LoadResult(Collections.emptyList(), -1);
        }

        long pillarSeed = -1;

        if (!lines.isEmpty()) {
            String last = lines.get(lines.size() - 1);
            if (!last.contains(",")) {
                try {
                    long potential = Long.parseLong(last);
                    if (potential >= 0 && potential <= 0xffffffffL) {
                        pillarSeed = potential;
                        lines.remove(lines.size() - 1);
                    }
                } catch (NumberFormatException ignored) {}
            }
        }

        List<Constraint> constraints = new ArrayList<>();
        int lineNum = 0;
        for (String l : lines) {
            lineNum++;
            Constraint c = new Constraint();
            if (registry.parseLine(l, c)) {
                constraints.add(c);
            } else {
                System.err.println("Warning: Malformed or unknown constraint on line " +
                        lineNum + ": \"" + l + "\". Skipping.");
            }
        }

        if (constraints.isEmpty() && pillarSeed == -1) {
            System.err.println("No valid constraints or pillar seed found.");
        }

        return new LoadResult(constraints, pillarSeed);
    }

    // =======================================================================
    // 12. Debug mode (check a specific seed against constraints.txt)
    // =======================================================================

    private static void debugSeed(long seed, List<Constraint> constraints) {
        DEBUG_VERBOSE = true;
        System.out.println("--- Debug mode for structure seed " + seed + " ---");
        StandaloneChunkRand rand = new StandaloneChunkRand();
        boolean allOk = true;
        int idx = 0;

        for (Constraint c : constraints) {
            idx++;
            System.out.println("Constraint " + idx + " (" + c.type +
                    " @ [" + c.chunkX + "," + c.chunkZ + "]):");
            boolean ok = checkConstraint(seed, c, rand);

            System.out.println("  -> " + (ok ? "PASS" : "FAIL"));
            if (!ok) allOk = false;
        }

        if (allOk) {
            System.out.println("All constraints satisfied ✅");
        } else {
            System.out.println("Some constraints FAILED ❌");
        }
    }

    // =======================================================================
    // 13. main()
    // =======================================================================

    public static void main(String[] args) {
        if (args.length < 1) {
            printUsage();
            System.exit(1);
        }

        String arg0 = args[0].trim();
        boolean isNumeric = arg0.matches("-?\\d+");

        // Initialize registry once
        StructureRegistry registry = new StructureRegistry();
        registry.initializeAllDeviceConstants();

        // Debug mode: java StructureSeedCracker <seed>
        if (isNumeric && args.length == 1) {
            long seed;
            try {
                seed = Long.parseLong(arg0);
            } catch (NumberFormatException e) {
                printUsage();
                System.exit(1);
                return;
            }

            String constraintsFile = "constraints.txt";
            File f = new File(constraintsFile);
            if (!f.exists()) {
                System.err.println("Debug mode: '" + constraintsFile + "' not found.");
                System.err.println("Create constraints.txt or use search mode with a file path.");
                System.exit(1);
            }

            LoadResult lr = loadConstraintsFromFile(constraintsFile, registry);
            if (lr.constraints.isEmpty()) {
                System.err.println("No constraints loaded from " + constraintsFile + ". Exiting.");
                System.exit(1);
            }

            debugSeed(seed & MASK_48, lr.constraints);
            return;
        }

        // Normal search mode: java StructureSeedCracker <constraints_file>
        String constraintsPath = arg0;

        long startTime = System.nanoTime();
        LoadResult lr = loadConstraintsFromFile(constraintsPath, registry);
        List<Constraint> constraints = lr.constraints;
        long pillarSeed = lr.pillarSeed;

        if (constraints.isEmpty() && pillarSeed == -1) {
            System.err.println("No work to do. Exiting.");
            System.exit(1);
        }

        List<Long> foundSeeds = new ArrayList<>();
        SearchStrategy strategy;

        try {
            if (pillarSeed != -1) {
                strategy = new PillarseedSearch(pillarSeed, constraints);
            } else {
                strategy = new StandardSearch(constraints);
            }
        } catch (IllegalArgumentException e) {
            System.err.println("Error initializing search strategy: " + e.getMessage());
            System.exit(1);
            return;
        }

        System.out.println(strategy.getDescription());
        strategy.execute(foundSeeds);

        long endTime = System.nanoTime();
        double seconds = (endTime - startTime) / 1_000_000_000.0;
        System.out.println("\n--- Search Complete in " + seconds + " seconds ---");

        if (foundSeeds.isEmpty()) {
            System.out.println("No structure seeds found.");
        } else {
            System.out.println("Found " + foundSeeds.size() + " valid seed(s). Writing to found_seeds.txt...");
            Collections.sort(foundSeeds);
            try (PrintWriter pw = new PrintWriter(new FileWriter("found_seeds.txt"))) {
                for (long s : foundSeeds) {
                    pw.println(s);
                }
            } catch (IOException e) {
                System.err.println("Error writing found_seeds.txt: " + e.getMessage());
            }
            System.out.println("Done.");
        }
    }
}
