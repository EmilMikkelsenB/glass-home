package se.glass.home;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** Last-known data on disk, shown instantly at start-up while fresh data loads: the home screen never waits on the network. */
final class Cache {
    private Cache() {}

    static void saveItems(Context c, String name, List<Jellyfin.Item> items) {
        try {
            JSONArray a = new JSONArray();
            for (Jellyfin.Item it : items) a.put(it.toJson());
            Files.write(file(c, name).toPath(), a.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException | JSONException ignored) {
        }
    }

    static List<Jellyfin.Item> loadItems(Context c, String name) {
        List<Jellyfin.Item> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(new String(Files.readAllBytes(file(c, name).toPath()), StandardCharsets.UTF_8));
            for (int i = 0; i < a.length(); i++) out.add(Jellyfin.Item.fromJson(a.getJSONObject(i)));
        } catch (IOException | JSONException ignored) {
        }
        return out;
    }

    private static File file(Context c, String name) {
        return new File(c.getCacheDir(), name + ".json");
    }
}
