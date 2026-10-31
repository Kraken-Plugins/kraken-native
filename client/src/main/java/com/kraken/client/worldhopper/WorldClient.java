package com.kraken.client.worldhopper;

import com.kraken.api.World;
import com.kraken.api.WorldType;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Fetches the world list the game's own world switcher shows, from the address the client fetches it from, and
 * decodes it.
 *
 * <p>The list is binary and big-endian: the size of the rest as an int, the number of worlds as an unsigned short,
 * then per world its number (unsigned short), its type flags (int, {@link WorldType}'s bits), its server and its
 * activity (null-terminated Windows-1252 strings), its location (a byte, {@link WorldRegion}'s numbers) and its
 * player count (short, -1 while offline).
 */
public class WorldClient {

    /**
     * The address the client fetches its world list from.
     */
    static final URI WORLD_LIST = URI.create("https://oldschool.config.runescape.com/slr.ws?order=LPWM");

    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final Charset CP1252 = Charset.forName("windows-1252");

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /**
     * Fetches and decodes the world list off the calling thread.
     *
     * @return the worlds sorted by number; completes exceptionally with an {@link IOException} if the list can't be
     * fetched, or a {@link WorldListException} if it can't be decoded
     */
    public CompletableFuture<List<World>> fetch() {
        HttpRequest request = HttpRequest.newBuilder(WORLD_LIST).timeout(TIMEOUT).GET().build();
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray()).thenApply(response -> {
            if (response.statusCode() != 200) {
                throw new WorldListException("the world list server answered " + response.statusCode());
            }
            return parse(response.body());
        });
    }

    /**
     * Decodes a world list.
     *
     * @param body the list as fetched
     * @return the worlds sorted by number
     * @throws WorldListException if the list is cut short or malformed
     */
    static List<World> parse(byte[] body) {
        try {
            ByteBuffer buffer = ByteBuffer.wrap(body);
            int size = buffer.getInt();
            if (size < 2 || size > buffer.remaining()) {
                throw new WorldListException("the world list says it holds " + size + " bytes, but "
                        + buffer.remaining() + " came");
            }
            buffer.limit(buffer.position() + size);
            int count = Short.toUnsignedInt(buffer.getShort());
            List<World> worlds = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                int id = Short.toUnsignedInt(buffer.getShort());
                int flags = buffer.getInt();
                String address = string(buffer);
                String activity = string(buffer);
                int location = Byte.toUnsignedInt(buffer.get());
                int players = buffer.getShort();
                worlds.add(World.builder()
                        .id(id)
                        .types(WorldType.fromMask(flags))
                        .address(address)
                        .activity(activity)
                        .location(location)
                        .playerCount(players)
                        .build());
            }
            worlds.sort(Comparator.comparingInt(World::getId));
            return List.copyOf(worlds);
        } catch (BufferUnderflowException e) {
            throw new WorldListException("the world list is cut short");
        }
    }

    /**
     * Reads a null-terminated Windows-1252 string.
     *
     * @param buffer the list, at the string
     * @return the string, without its terminator
     */
    private static String string(ByteBuffer buffer) {
        int start = buffer.position();
        while (buffer.get() != 0) {
            // Find the terminator; running out throws BufferUnderflowException
        }
        int end = buffer.position() - 1;
        return new String(buffer.array(), buffer.arrayOffset() + start, end - start, CP1252);
    }

    /**
     * The world list couldn't be decoded.
     */
    static final class WorldListException extends RuntimeException {

        /**
         * Makes the exception.
         *
         * @param message what was wrong
         */
        WorldListException(String message) {
            super(message);
        }
    }
}
