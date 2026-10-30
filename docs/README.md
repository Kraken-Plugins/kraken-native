# Kraken Native Documentation

New here? Start with the [README](../README.md) to get Kraken running.

## Using Kraken

| Page | What's in it |
|---|---|
| [Using the client](client.md) | Launch options, the window and sidebar, settings, the console, overlays, the native hook, and the files Kraken writes |
| [Logging in with a Jagex account](jagex-accounts.md) | Passing your Jagex account session to the game when Kraken starts it |
| [Plugins](plugins.md) | The built-in plugins and their settings |
| [Dev tools](dev-tools.md) | The tools that show what Kraken reads from the game |
| [Mapping a new game build](updater.md) | Running the updater when the game updates, and reading its results |
| [Troubleshooting](troubleshooting.md) | Build errors, attach errors and other messages, and what to do about them |

## Building On Kraken

| Page | What's in it |
|---|---|
| [Writing a plugin](writing-plugins.md) | A walk-through of a complete plugin, then settings, events, overlays and differences from RuneLite |
| [The API](api.md) | What the API reads, how reads behave, threads, highlights, menu actions, and using it without the client |
| [Events](events.md) | Every event, when it's posted and what it carries |

## How It Works

Developer documentation, on how the updater, the API, the client and the native hook fit together, will go in
`docs/developer/`. Until then, the module READMEs cover the internals: [the updater](../updater/README.md) and
[the native hook](../client/src/main/native/kraken_hook/README.md).
