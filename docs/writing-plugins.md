# Writing a Plugin

A Kraken plugin has the same shape as a RuneLite plugin: a `Plugin` subclass with a `@PluginDescriptor`, injected
services, `startUp` and `shutDown`, `@Subscribe` methods for game events, a settings interface and overlays. This page
walks through one, then covers each part. The [API guide](api.md) covers what you can read from the game.

## Where Plugins Live

The simplest place is a package of its own under `client/src/main/java/com/kraken/client/plugins/`, like the built-in
plugins. Kraken finds every class with a `@PluginDescriptor` there when it starts, so there's nothing to register.

A plugin class elsewhere on the classpath can be loaded with `--plugin`:

```powershell
./gradlew :client:run --args="--plugin com.example.MyPlugin"
```

Or start Kraken from a `main` of your own, with your plugins added to the built-in ones:

```java
public static void main(String[] args) throws Exception {
    KrakenClient.launch(args, MyPlugin.class);
}
```

The client and API aren't published to a Maven repository yet, so code outside the `plugins` package still has to be
built alongside them, for example in a Gradle project of its own inside this build that depends on `project(':client')`.

## A First Plugin

The Tick Counter plugin (`plugins/tickcounter`) is the smallest complete example. It counts game ticks, shows the count
in a panel and logs it. It has three parts.

### Settings

An interface extending `Config`, with a `@ConfigGroup` naming where its values are kept. Each `@ConfigItem` method's
`default` body is the default value. The settings page is built from it.

```java
@ConfigGroup("tickcounter")
public interface TickCounterConfig extends Config {

    @ConfigItem(keyName = "showOverlay", name = "Show panel", description = "Show the count in a panel over the game",
            position = 0)
    default boolean showOverlay() {
        return true;
    }

    @Range(max = 60)
    @Units(Units.MINUTES)
    @ConfigItem(keyName = "logInterval", name = "Log every",
            description = "How often to log the count to the console. 0 never logs it.", position = 1)
    default int logInterval() {
        return 1;
    }
}
```

### The Plugin

```java
@Log(topic = "kraken")
@PluginDescriptor(
        name = "Tick Counter",
        description = "Counts game ticks while you're logged in, shows the count over the game and logs it to the "
                + "console",
        tags = {"tick", "timer", "example"},
        enabledByDefault = false
)
public class TickCounterPlugin extends Plugin {

    private static final int TICKS_PER_MINUTE = 100;

    @Inject
    private Client client;

    @Inject
    private ClientThread clientThread;

    @Inject
    private TickCounterConfig config;

    @Inject
    private OverlayManager overlayManager;

    @Inject
    private TickCounterOverlay overlay;

    @Getter(AccessLevel.PACKAGE)
    private int ticks;

    @Provides
    TickCounterConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(TickCounterConfig.class);
    }

    @Override
    protected void startUp() {
        overlayManager.add(overlay);
        clientThread.invoke(() -> ticks = 0);
    }

    @Override
    protected void shutDown() {
        overlayManager.remove(overlay);
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event) {
        if (event.getGameState() == GameState.LOGIN_SCREEN || event.getGameState() == GameState.HOPPING) {
            ticks = 0;
        }
    }

    @Subscribe
    public void onGameTick(GameTick event) {
        ticks++;
        int every = config.logInterval() * TICKS_PER_MINUTE;
        if (every > 0 && ticks % every == 0) {
            log.info("Tick counter: " + ticks + " ticks");
        }
    }
}
```

* The `@Provides` method is what lets you `@Inject` the config. Without it the plugin fails to load.
* `startUp` and `shutDown` run on the Swing thread. The event handlers run on the client thread, so the plugin resets
  the count through `clientThread.invoke` to keep `ticks` on one thread.
* Kraken registers the `@Subscribe` methods after `startUp` returns, and unregisters them before `shutDown`.
* Overlays are not added for you: add them in `startUp` and remove them in `shutDown`.

### The Overlay

An `OverlayPanel` is a panel of lines that the user can move and resize. Fill `panelComponent` each frame and return
`super.render(graphics)`; return `null` to draw nothing.

```java
class TickCounterOverlay extends OverlayPanel {

    private final TickCounterPlugin plugin;
    private final TickCounterConfig config;
    private final Client client;

    @Inject
    TickCounterOverlay(TickCounterPlugin plugin, TickCounterConfig config, Client client) {
        super(plugin);
        this.plugin = plugin;
        this.config = config;
        this.client = client;
        setPosition(OverlayPosition.TOP_LEFT);
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        if (!config.showOverlay() || !client.isLoggedIn()) {
            return null;
        }
        panelComponent.getChildren().add(TitleComponent.builder().text("Tick counter").color(Theme.ACCENT).build());
        panelComponent.getChildren().add(LineComponent.builder()
                .left("Ticks:").right(String.valueOf(plugin.getTicks())).build());
        return super.render(graphics);
    }
}
```

### Running It

Start Kraken, open the **Plugins** tab and switch the plugin on. Plugins load once Kraken has attached to the game, so
they only appear then. Your plugin's on/off state, and its settings, are saved in
`~/.kraken-native/settings.properties`. Use `"-Dkraken.settings=<file>"` to keep a separate file while you develop.

## The Descriptor

| Attribute | Default | What it does |
|---|---|---|
| `name` | Required | The name in the Plugins tab |
| `description` | `""` | Shown when hovering over the plugin |
| `tags` | None | Extra words the Plugins search matches |
| `enabledByDefault` | `true` | Whether the plugin is on before the user has switched it |
| `conflicts` | None | Names of plugins that can't run alongside this one. Switching this on offers to switch them off |
| `hidden` | `false` | Leaves the plugin out of the Plugins tab. It still loads and starts by its saved setting |
| `developerPlugin` | `false` | Loads the plugin only with `--developer-mode` |

## What You Can Inject

| Type | What it is |
|---|---|
| `Client` | The game. See the [API guide](api.md) |
| `ClientThread` | Runs code on the client thread: `invoke`, `invokeLater`, `invokeAtTickEnd` |
| `EventBus` | The bus events are posted on, for posting your own or subscribing other objects |
| `ConfigManager` | Reads and writes settings: `getConfig`, `getConfiguration`, `setConfiguration` |
| `OverlayManager` | Adds and removes overlays |
| `GameSession` | The attached game session |
| `KeyManager` | Which keys are held while the game has the keyboard; see [Keys](#keys) |
| `ScheduledExecutorService` | A shared background scheduler, for work that shouldn't hold up the client thread |
| Your plugin, config and overlays | Each plugin has its own injector, so its classes can inject each other |

## Settings

| Annotation | Goes on | What it does |
|---|---|---|
| `@ConfigGroup("name")` | The interface | Where the values are kept, as `name.key` |
| `@ConfigItem` | A method | One setting: `keyName`, `name`, `description`, and optionally `position`, `section`, `hidden`, `secret` (masks text) and `warning` (asks before a change) |
| `@ConfigSection` | A `String` field | A collapsible group: `name`, `description`, `position`, `closedByDefault`. Items join it with `section = <the field>` |
| `@Range(min, max)` | A number | Bounds the spinner |
| `@Units(Units.PIXELS)` | A number | A suffix such as `px`, `ms`, ` ticks` or `fps` |
| `@Alpha` | A `Color` | Lets the colour picker set transparency |
| `@ShowIf(key, values)` | An item or section | Shows it only while another item has one of the values |
| `@Suggestions` | A `String` | Adds a drop-down your plugin fills when it opens |
| `@Unsupported("reason")` | An item | Greys it out, with a tooltip saying it's currently unsupported and why. For a setting your plugin can't act on yet |

Supported types are `boolean`, `int`, `long`, `float`, `double`, `String`, enums, `Set`s of an enum, `Color`,
`Dimension`, `Point`, `Rectangle` (no control, so for hidden items), `Keybind` and `Button`.

A `void` method with the same `keyName` and one parameter is a setter. Changes post `ConfigChanged`, usually on the
Swing thread; check `event.getGroup()` and hand any game work to the client thread.

### Buttons, Drop-Downs and Conditional Items

The Action Tester plugin uses all three; shortened from `ActionTesterConfig`:

```java
@ShowIf(key = "test", values = {"MOUSE_CLICK"})
@ConfigItem(keyName = "clickPoint", name = "Click at", description = "The canvas point to click, x then y.",
        position = 18)
default Point clickPoint() {
    return new Point(400, 300);
}

@Suggestions
@ConfigItem(keyName = "npc", name = "NPC", description = "The NPC to act on, by name.", position = 5)
default String npc() {
    return "";
}

@ConfigItem(keyName = "run", name = "Run", description = "Run the action once now.", position = 30)
Button run();
```

A button posts `ConfigButtonClicked` when pressed, and a suggestions drop-down posts `ConfigSuggestionsRequested`
each time it opens. Answer with `suggest`, from any thread:

```java
@Subscribe
public void onConfigButtonClicked(ConfigButtonClicked event) {
    if (MyConfig.GROUP.equals(event.getGroup()) && "run".equals(event.getKey())) {
        clientThread.invoke(this::run);
    }
}

@Subscribe
public void onConfigSuggestionsRequested(ConfigSuggestionsRequested event) {
    if (MyConfig.GROUP.equals(event.getGroup()) && "npc".equals(event.getKey())) {
        clientThread.invoke(() -> event.suggest(client.getNpcs().stream().map(NPC::getName).distinct().toList()));
    }
}
```

## Keys

The game takes the keyboard, so Kraken never gets its key events. `KeyManager` reads which keys are held instead, while
the game has the keyboard. It can't stop the game from seeing a key, so a hotkey should be one the game doesn't use.

Give the user a `Keybind` setting and listen for it with a `HotkeyListener`; both methods run on the client thread:

```java
@ConfigItem(keyName = "hotkey", name = "Hotkey", description = "Hold to show everything")
default Keybind hotkey() {
    return Keybind.ALT;
}
```

```java
private final HotkeyListener hotkeyListener = new HotkeyListener(() -> config.hotkey()) {
    @Override
    public void hotkeyPressed() {
        showAll = true;
    }

    @Override
    public void hotkeyReleased() {
        showAll = false;
    }
};

@Override
protected void startUp() {
    keyManager.registerHotkeyListener(hotkeyListener);
}

@Override
protected void shutDown() {
    keyManager.unregisterHotkeyListener(hotkeyListener);
}
```

`keyManager.isKeyPressed(KeyEvent.VK_SHIFT)` reads one key now, from any thread.

## Events and the Client Thread

Any public or private method annotated `@Subscribe` that takes one event is a handler. [Events](events.md) lists them
all. Game events arrive on the client thread; settings events (`ConfigChanged`, `ConfigButtonClicked`,
`ConfigSuggestionsRequested`) arrive on the Swing thread.

Keep a plugin's game state on the client thread, in handlers, `clientThread.invoke` tasks and overlay `render`
methods, and it needs no locking.

Spawn events only report what appears after tracking starts. A plugin switched on mid-game doesn't get spawns for what
was already there, so read it when it starts, for example:

```java
@Override
protected void startUp() {
    clientThread.invoke(this::rebuild);
}

private void rebuild() {
    highlighted.clear();
    for (NPC npc : client.getNpcs()) {
        if (matches(npc)) {
            highlighted.add(npc);
        }
    }
}
```

Clear what you track on `GameStateChanged` with `GameState.LOADING`: a map load posts spawns for the new area but no
despawns for the old one.

## Overlays

Extend `Overlay` and override `render(Graphics2D)`, or extend `OverlayPanel` for a panel of lines. Coordinates are game
canvas pixels. `render` runs on the client thread, at the overlay frame rate set in Settings.

| Setting | Values |
|---|---|
| `setPosition` | `TOP_LEFT` (default), `TOP_CENTER`, `TOP_RIGHT`, `BOTTOM_LEFT`, `BOTTOM_RIGHT`, `CANVAS_TOP_RIGHT`, or `DYNAMIC` for drawing anywhere, such as on things in the scene |
| `setLayer` | `ABOVE_SCENE`, `UNDER_WIDGETS` (default), `ABOVE_WIDGETS`, `ALWAYS_ON_TOP`. Every layer draws over the game's interfaces; layers only order overlays among themselves |
| `setPriority` | `Overlay.PRIORITY_LOW` to `PRIORITY_HIGHEST`. Higher sits nearer its corner, or on top for `DYNAMIC` |
| `setMovable`, `setResizable` | Whether Alt-drag can move or resize it. Panels are both |

To draw on things in the scene, project them to the canvas:

* `npc.getCanvasTilePoly()`, `tileObject.getCanvasTilePoly()`, `getCanvasTextLocation(...)`;
* `Perspective.getCanvasTilePoly(client, localPoint)`, `Perspective.localToCanvas(client, localPoint, plane)`;
* `LocalPoint.fromWorld(client.getTopLevelWorldView(), worldPoint)` to turn a world tile into a local point.

`OverlayUtil` draws the common shapes: `renderPolygon`, `renderTextLocation`, `renderActorOverlay`,
`renderActorTextOverlay`, `renderTileOverlay` and `renderImageLocation`. The Tile Indicators overlay is a short example:

```java
Player player = client.getLocalPlayer();
WorldPoint position = player == null ? null : player.getWorldLocation();
LocalPoint local = position == null ? null : LocalPoint.fromWorld(client.getTopLevelWorldView(), position);
Polygon poly = local == null ? null : Perspective.getCanvasTilePoly(client, local);
if (poly != null) {
    OverlayUtil.renderPolygon(graphics, poly, config.highlightCurrentColor(), config.currentTileFillColor(),
            new BasicStroke((float) config.currentTileBorderWidth()));
}
```

Each overlay's placement is saved under its class name, so give your overlays distinct names.

For outlines on NPCs, objects, items and tiles, [highlights](api.md#highlights) look better: the game draws them
itself, around the model, following the camera. They need the native hook, so a plugin can draw its own outline as a
fallback while `client.getHighlights().isAvailable()` is false, as NPC Indicators does.

## Logging

Log through `java.util.logging`, for example with Lombok's `@Log(topic = "kraken")`. Messages at `INFO` and above
appear in Kraken's console.

## When Things Go Wrong

* If `startUp` throws, Kraken logs it, runs `shutDown`, marks the plugin **failed** in the Plugins tab and switches it
  off.
* If the plugin can't be built, for instance an `@Inject` of a config without a `@Provides`, it's listed as **failed**
  and can't be switched on.
* If a handler throws, the exception is logged and the other handlers still run.

## Depending on Another Plugin

`@PluginDependency(OtherPlugin.class)` loads and starts the other plugin first, and gives yours the services it shares.
The other plugin shares them by overriding `getPublicModule()` to return a Guice module. If it returns `null`, plugins
that depend on it fail to load.

## Coming From RuneLite

Most of RuneLite's plugin API carries over, under `com.kraken` package names. The differences you're likely to meet:

| RuneLite | Kraken |
|---|---|
| `net.runelite.api.*`, `net.runelite.client.*` | `com.kraken.api.*` for the game, `com.kraken.client.*` for plugins, settings and overlays |
| `net.runelite.client.eventbus.Subscribe` | `com.kraken.api.eventbus.Subscribe` |
| `clientThread.invoke(() -> value)` returning a value | Only `Runnable` and `BooleanSupplier` tasks |
| `client.menuAction(...)` | `client.getActions()`, which needs the native hook and reports whether it sent |
| `@ConfigItem(hidden = ...)` with `hide`/`unhide` | `@ShowIf` |
| Keybind settings and `KeyManager` | `Keybind` settings and `KeyManager` work, but Kraken only sees which keys are held: register a `HotkeyListener`, not a `KeyListener`, and a key can't be consumed or remapped |
| Adding menu entries, consuming clicks | Not supported: Kraken can't change the game's menu |
| A setting the client can't back | Keep it and mark it `@Unsupported("reason")`; the settings page greys it out |
| `ItemManager`, `SpriteManager`, info boxes, `Notifier`, `ClientToolbar` | Not available yet |
| Minimap and clickbox helpers, actor hulls | Not available yet |
| Plugin on/off key | `kraken.<class name in lower case>`, such as `kraken.tickcounterplugin` |
