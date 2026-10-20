package com.kraken.client.ui.devtools.tools;

import com.kraken.client.ui.devtools.DevTool;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.kordamp.ikonli.materialdesign2.*;

import java.util.List;

import static com.kraken.client.ui.devtools.Category.*;

/**
 * Every dev tool, in sidebar order within each category. Add new tools here. Planned tools hold a place for what the
 * API can't read yet; swap each for a real tool as its API lands.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class DevToolCatalog {

    public static List<DevTool<?>> create() {
        return List.of(
                new NpcsTool(),
                new PlayersTool(),
                new InteractingTool(),
                new ProjectilesTool(),
                PlannedTool.both("graphics", "Graphics objects", ACTORS, MaterialDesignS.SPARKLES,
                        "Spot animations playing in the scene, with their ids.",
                        "spot animation reads"),
                PlannedTool.both("worldEntities", "World entities", ACTORS, MaterialDesignS.SAIL_BOAT,
                        "Moving sub-worlds such as boats, with their id, position and bounds.",
                        "world entity reads"),

                new ClientStateTool(),
                new SkillsTool(),
                new InventoryTool(),
                new VarInspectorTool(),

                new GameObjectsTool(),
                new WallsTool(),
                new GroundItemsTool(),
                new CameraTool(),

                new LocationTool(),
                BordersTool.zones(MaterialDesignG.GRID),
                BordersTool.mapSquares(MaterialDesignG.GRID_LARGE),
                new TileFlagsTool(),
                MovementTool.walk(MaterialDesignW.WALK),
                MovementTool.sight(MaterialDesignR.RAY_START_END),

                new WidgetInspectorTool(),
                PlannedTool.window("sounds", "Sound effects", INTERFACE, MaterialDesignV.VOLUME_HIGH,
                        "A log of sound effects as the client plays them.",
                        "a sound event source, which likely needs in-process hooks"),

                new EventLogTool()
        );
    }
}
