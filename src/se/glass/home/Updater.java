package se.glass.home;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Self-update from GitHub releases (made by .github/workflows/release.yml). Config on the TV:
 * /sdcard/Android/data/se.glass.home/files/update.json
 * {"repo": "EmilMikkelsenB/glass-home", "token": "<only for private repos: fine-grained, Contents read-only>"}
 * Without the file the public repo is used, no token needed.
 */
final class Updater {
    static final class Release {
        String name, notes = "";
        long versionCode, assetId;
    }

    private final String repo, token;

    private Updater(String repo, String token) {
        this.repo = repo;
        this.token = token;
    }

    /** update.json if present (e.g. a private fork with a token), else the public repo without a token. */
    static Updater load(Context c, String defaultRepo) {
        File f = new File(c.getExternalFilesDir(null), "update.json");
        if (!f.exists()) return new Updater(defaultRepo, "");
        try {
            JSONObject o = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            return new Updater(o.getString("repo"), o.optString("token"));
        } catch (IOException | JSONException e) {
            Log.w("GlassHome", "bad update.json", e);
            return null;
        }
    }

    /** Latest release, or null if it has no APK or no versionCode marker in its notes. */
    Release latest() throws IOException, JSONException {
        HttpURLConnection c = api("/repos/" + repo + "/releases/latest", "application/vnd.github+json");
        JSONObject r;
        try (InputStream in = c.getInputStream()) {
            r = new JSONObject(read(in));
        } finally {
            c.disconnect();
        }
        Matcher m = Pattern.compile("versionCode=(\\d+)").matcher(r.optString("body"));
        if (!m.find()) return null;
        Release rel = new Release();
        rel.name = r.optString("tag_name").replaceFirst("^v", "");
        rel.versionCode = Long.parseLong(m.group(1));
        rel.notes = r.optString("body").replaceAll("(?s)<!--.*?-->", "").replaceAll("\\*\\*|`|#+ ", "").trim();
        JSONArray assets = r.optJSONArray("assets");
        for (int i = 0; assets != null && i < assets.length(); i++) {
            if (assets.getJSONObject(i).optString("name").equals("glass-home.apk")) rel.assetId = assets.getJSONObject(i).getLong("id");
        }
        return rel.assetId != 0 ? rel : null;
    }

    /** Downloads the release APK. GitHub redirects to a signed storage URL that must NOT get our token. */
    File download(Release rel, File dir) throws IOException {
        HttpURLConnection c = api("/repos/" + repo + "/releases/assets/" + rel.assetId, "application/octet-stream");
        String location = c.getHeaderField("Location");
        if (location != null) {
            c.disconnect();
            c = (HttpURLConnection) new URL(location).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(30000);
        }
        File apk = new File(dir, "update.apk");
        try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(apk)) {
            byte[] b = new byte[65536];
            for (int n; (n = in.read(b)) > 0; ) out.write(b, 0, n);
        } finally {
            c.disconnect();
        }
        return apk;
    }

    /** Hands the APK to Android's installer; InstallReceiver then shows the system "update this app?" dialog. */
    static void install(Context ctx, File apk) throws IOException {
        PackageInstaller pi = ctx.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(ctx.getPackageName());
        int id = pi.createSession(params);
        try (PackageInstaller.Session s = pi.openSession(id)) {
            try (OutputStream out = s.openWrite("base.apk", 0, apk.length()); InputStream in = new FileInputStream(apk)) {
                byte[] b = new byte[65536];
                for (int n; (n = in.read(b)) > 0; ) out.write(b, 0, n);
                s.fsync(out);
            }
            PendingIntent done = PendingIntent.getBroadcast(ctx, id, new Intent(ctx, InstallReceiver.class),
                    PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            s.commit(done.getIntentSender());
        }
    }

    private HttpURLConnection api(String path, String accept) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL("https://api.github.com" + path).openConnection();
        c.setInstanceFollowRedirects(false);
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        c.setRequestProperty("Accept", accept);
        c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        if (!token.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + token);
        int code = c.getResponseCode();
        if (code >= 400) throw new IOException("GitHub HTTP " + code + " for " + path);
        return c;
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        for (int n; (n = in.read(b)) > 0; ) buf.write(b, 0, n);
        return buf.toString("UTF-8");
    }
}
