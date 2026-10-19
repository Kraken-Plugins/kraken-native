package com.kraken.client.devtools.tools;

import com.kraken.client.devtools.DevTool;
import org.kordamp.ikonli.materialdesign2.*;

import java.util.List;

import static com.kraken.client.devtools.Category.*;

/**
 * Every dev tool, in sidebar order within each category. Add new tools here. Planned tools hold a place for what the
 * API can't read yet; swap each for a real tool as its API lands. Many of those reads are already in the mapping.
 */
public final class DevToolCatalog {
    private static final String PROJECTION = "world-to-screen projection";
    private static final String COLLISION = "collision map reads (mapped) and " + PROJECTION;

    private DevToolCatalog() {
    }

    public static List<DevTool<?>> create() {
        return List.of(
                new NpcsTool(),
                new PlayersTool(),
                new InteractingTool(),
                PlannedTool.both("projectiles", "Projectiles", ACTORS, MaterialDesignB.BULLSEYE_ARROW,
                        "Projectiles in flight with their id, source and target.",
                        "projectile list reads and " + PROJECTION),
                PlannedTool.both("graphics", "Graphics objects", ACTORS, MaterialDesignS.SPARKLES,
                        "Spot animations playing in the scene, with their ids.",
                        "spot animation reads and " + PROJECTION),
                PlannedTool.both("worldEntities", "World entities", ACTORS, MaterialDesignS.SAIL_BOAT,
                        "Moving sub-worlds such as boats, with their id, position and bounds.",
                        "world entity reads and " + PROJECTION),

                new ClientStateTool(),
                new SkillsTool(),
                new InventoryTool(),
                new VarInspectorTool(),

                PlannedTool.both("gameObjects", "Game objects", SCENE, MaterialDesignC.CUBE_OUTLINE,
                        "Scenery such as trees, rocks and doors, with its id and name.",
                        "scene loc reads (mapped) and " + PROJECTION),
                PlannedTool.overlay("walls", "Walls", SCENE, MaterialDesignW.WALL,
                        "Wall objects on each tile, with their ids.",
                        "scene loc reads (mapped) and " + PROJECTION),
                PlannedTool.both("groundItems", "Ground items", SCENE, MaterialDesignP.PACKAGE_VARIANT_CLOSED,
                        "Items on the ground with their id, name and quantity.",
                        "ground item reads (mapped) and " + PROJECTION),
                PlannedTool.window("camera", "Camera", SCENE, MaterialDesignC.CAMERA_OUTLINE,
                        "The camera's position, pitch, yaw and zoom.",
                        "camera reads (mapped)"),

                new LocationTool(),
                PlannedTool.overlay("zoneBorders", "Zone borders", MAP, MaterialDesignG.GRID,
                        "Outlines the 8×8 zones around you.", PROJECTION),
                PlannedTool.overlay("mapSquares", "Map squares", MAP, MaterialDesignG.GRID_LARGE,
                        "Outlines the 64×64 map squares around you.", PROJECTION),
                PlannedTool.overlay("tileFlags", "Tile flags", MAP, MaterialDesignF.FLAG_OUTLINE,
                        "Collision and movement flags on each tile.", COLLISION),
                PlannedTool.overlay("validMovement", "Valid movement", MAP, MaterialDesignW.WALK,
                        "Highlights the tiles you can walk to from where you stand.", COLLISION),
                PlannedTool.overlay("lineOfSight", "Line of sight", MAP, MaterialDesignR.RAY_START_END,
                        "Highlights the tiles you have line of sight to.", COLLISION),

                PlannedTool.both("widgets", "Widget inspector", INTERFACE, MaterialDesignW.WIDGETS_OUTLINE,
                        "Browse the interface tree, and highlight the selected widget in game.",
                        "interface tree reads (mapped)"),
                PlannedTool.window("sounds", "Sound effects", INTERFACE, MaterialDesignV.VOLUME_HIGH,
                        "A log of sound effects as the client plays them.",
                        "a sound event source, which likely needs in-process hooks"),

                new EventLogTool()
        );
    }
}
