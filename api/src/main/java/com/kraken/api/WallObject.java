package com.kraken.api;

/**
 * A wall, fence or door on a tile's edge, as RuneLite's {@code WallObject}. Its orientation and config bits aren't
 * read, so RuneLite's {@code getOrientationA}, {@code getOrientationB} and {@code getConfig} are left out.
 */
public interface WallObject extends TileObject {
}
