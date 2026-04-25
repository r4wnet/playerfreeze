# Player Freeze

A small Paper plugin for Minecraft screensharing.

## Commands

- `/freeze <player>` freezes an online player and gives them Blindness.
- `/unfreeze <player>` unfreezes the player and removes Blindness.
- `/admit` can only be used while frozen, runs `/ban <player> 14d Cheating - Admitted` from console, and removes the player from the freeze list afterwards.
- Logging out while frozen runs `/ban <player> 30d Logged out whilst frozen` from console and removes the player from the freeze list.
- Frozen players see a red `You are frozen!` title with a white `Logging out will result in a ban` subtitle.

Frozen players are also removed from the freeze list after a staff member runs `/ban <player> ...`.

## Configuration

- `config.json` controls commands that are run by `/admit` and frozen logouts.
- `config.json` also controls whether frozen players receive Blindness and whether the screen title is shown.
- `messages.json` controls the plugin messages.
- `messages.json` controls the screen title through `freezeTitle` and `freezeSubtitle`.
- Commands and messages support `[User]`, `{player}`, and `%player%` placeholders.
- Messages support `&` color codes, formatting codes like `&l`/`&r`, and hex colors like `&#ff0000`.

## Permission

- `playerfreeze.use`

By default, only operators can use the staff commands.

## Build

```powershell
gradle build
```

The finished plugin is created at `build/libs/player-freeze-1.0.0.jar`.
