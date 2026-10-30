package com.kraken.client.plugins.groundmarkers;

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import java.awt.Color;
import java.io.IOException;

/**
 * Writes a colour into marked tile JSON as {@code "#AARRGGBB"}, and reads that, {@code "#RRGGBB"}, or the older
 * {@code {"value": argb, ...}} object form other exports may hold.
 */
class ColorTypeAdapter extends TypeAdapter<Color> {

    @Override
    public void write(JsonWriter out, Color value) throws IOException {
        if (value == null) {
            out.nullValue();
            return;
        }
        out.value(String.format("#%08X", value.getRGB()));
    }

    @Override
    public Color read(JsonReader in) throws IOException {
        JsonToken token = in.peek();
        if (token == JsonToken.NULL) {
            in.nextNull();
            return null;
        }
        if (token == JsonToken.STRING) {
            String text = in.nextString();
            if (!text.startsWith("#")) {
                return null;
            }
            String hex = text.substring(1);
            try {
                return new Color(Integer.parseUnsignedInt(hex, 16), hex.length() == 8);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        if (token == JsonToken.BEGIN_OBJECT) {
            double argb = 0;
            in.beginObject();
            while (in.hasNext()) {
                if ("value".equals(in.nextName())) {
                    argb = in.nextDouble();
                } else {
                    in.skipValue();
                }
            }
            in.endObject();
            return new Color((int) argb, true);
        }
        in.skipValue();
        return null;
    }
}
