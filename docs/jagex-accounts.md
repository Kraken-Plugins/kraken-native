# Logging In With a Jagex Account

When you press Play in the Jagex Launcher, the launcher logs you in and hands the session to `osclient.exe` through
environment variables. Kraken starts `osclient.exe` itself, so the launcher isn't there to set them. You set them
instead, and Kraken passes its own environment to the game unchanged.

Without them the game shows the legacy login screen, which doesn't accept Jagex accounts. Accounts that still log in
with a username and password don't need any of this.

## The Variables

| Variable | What it holds |
|---|---|
| `JX_SESSION_ID` | Your Jagex account's login session. Treat it like a password. |
| `JX_CHARACTER_ID` | The character to log in as. |
| `JX_DISPLAY_NAME` | That character's display name. |

The game also reads `JX_ACCESS_TOKEN` and `JX_REFRESH_TOKEN`, but Jagex account logins don't need them.

## Getting the Values

The simplest way is to have RuneLite write them out once:

1. In the Jagex Launcher, choose RuneLite as the Old School client.
2. Run **RuneLite (configure)** from the Start menu and add `--insecure-write-credentials` to **Client arguments**.
3. Pick the character in the Jagex Launcher and press Play. RuneLite saves the variables, under the same names, to
   `%USERPROFILE%\.runelite\credentials.properties`.
4. Copy the values, then remove the flag and delete `credentials.properties`. While that file exists, RuneLite logs in
   with it instead of with the launcher.

Or start the official client from the launcher and open `osclient.exe`'s properties in
[Process Explorer](https://learn.microsoft.com/sysinternals/downloads/process-explorer). The variables are listed on
its **Environment** tab.

## Starting Kraken With Them

Set them in PowerShell and start Kraken from the same window. For the installed app:

```powershell
$env:JX_SESSION_ID   = "<session id>"
$env:JX_CHARACTER_ID = "<character id>"
$env:JX_DISPLAY_NAME = "<display name>"
& "$env:LOCALAPPDATA\Kraken\Kraken.exe"
```

From a checkout, run `./gradlew :client:run` as the last line instead. The variables last until you close that window.
In IntelliJ, put them in the run configuration's environment variables instead.

### From the Start Menu

Kraken started from the Start menu or a shortcut sees only your Windows account's environment variables. To log in
that way, save the variables to your account once:

```powershell
[Environment]::SetEnvironmentVariable("JX_SESSION_ID", "<session id>", "User")
[Environment]::SetEnvironmentVariable("JX_CHARACTER_ID", "<character id>", "User")
[Environment]::SetEnvironmentVariable("JX_DISPLAY_NAME", "<display name>", "User")
```

Programs started after that see them. Windows keeps them unencrypted in your profile, where every program you run can
read them. To remove them, run the same lines with `$null` in place of each value.

To play a different character on the same Jagex account, change `JX_CHARACTER_ID` and `JX_DISPLAY_NAME`. A character
on another Jagex account needs that account's session ID.

## Keep Them Private

Don't commit or share these values: anyone who has your session ID can log in as you. If they leak, use **End
sessions** in your account settings on runescape.com to invalidate them.
