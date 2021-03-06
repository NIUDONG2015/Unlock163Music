package bin.xposed.Unblock163MusicClient;

import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.xbill.DNS.Lookup;
import org.xbill.DNS.Record;
import org.xbill.DNS.SimpleResolver;
import org.xbill.DNS.TextParseException;
import org.xbill.DNS.Type;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import de.robv.android.xposed.XposedHelpers;

import static de.robv.android.xposed.XposedHelpers.findClass;
import static de.robv.android.xposed.XposedHelpers.findConstructorExact;


final public class Utility {
    final private static Map<String, Integer> QUALITY_MAP = new LinkedHashMap<String, Integer>() {
        {
            put("h", 320000);
            put("m", 160000);
            put("l", 96000);
            put("a", 64000);
        }
    };
    final private static Pattern REX_ST = Pattern.compile("\"st\":-?\\d+");
    final private static Pattern REX_PL = Pattern.compile("\"pl\":\\d+");
    final private static Pattern REX_DL = Pattern.compile("\"dl\":\\d+");
    final private static Pattern REX_SUBP = Pattern.compile("\"subp\":\\d+");
    final private static Pattern REX_FEE = Pattern.compile("\"fee\":\\d+");

    protected static boolean OVERSEA_MODE_ENABLED;
    protected static Field FIELD_utils_c;
    private static SimpleResolver CN_DNS_RESOVLER;
    private static boolean NEED_TO_CLEAN_DNS_CACHE;
    public static Class CLASS_utils_NeteaseMusicUtils;
    private static Constructor CONSTRUCTOR_i_f;
    private static Constructor CONSTRUCTOR_i_f1;

    protected static boolean init(ClassLoader classLoader) throws NoSuchFieldException {
        CLASS_utils_NeteaseMusicUtils = XposedHelpers.findClass("com.netease.cloudmusic.utils.NeteaseMusicUtils", classLoader);
        CONSTRUCTOR_i_f = findConstructorExact(findClass(Main.HOOK_CONSTRUCTOR, classLoader), String.class, Map.class);
        CONSTRUCTOR_i_f1 = findConstructorExact(findClass(Main.HOOK_CONSTRUCTOR, classLoader), String.class);
        FIELD_utils_c = findClass(Main.HOOK_UTILS, classLoader).getDeclaredField("c");//3.1.4
        FIELD_utils_c.setAccessible(true);
        return true;
    }

    protected static String modifyDetailApi(String originalContent) {
        String modified = originalContent;
        modified = REX_ST.matcher(modified).replaceAll("\"st\":0");
        modified = REX_PL.matcher(modified).replaceAll("\"pl\":320000");
        modified = REX_DL.matcher(modified).replaceAll("\"dl\":320000");
        modified = REX_SUBP.matcher(modified).replaceAll("\"subp\":1");
        modified = REX_FEE.matcher(modified).replaceAll("\"fee\":0");
        return modified;
    }

    protected static String modifyPlayerApi(String url, String originalContent) throws JSONException, IllegalAccessException, InstantiationException, InvocationTargetException, MalformedURLException {
        JSONObject originalJson = new JSONObject(originalContent);
        JSONArray originalSongArray = originalJson.getJSONArray("data");

        List<Long> blockedSongIdList = new ArrayList<>();

        for (int i = 0; i < originalSongArray.length(); i++) {
            if (originalSongArray.getJSONObject(i).isNull("url") && originalSongArray.getJSONObject(i).getInt("fee") == 0) {
                Long songId = originalSongArray.getJSONObject(i).getLong("id");
                blockedSongIdList.add(songId);
            }
        }

        if (blockedSongIdList.size() > 0) {
            int expectBitrate = Integer.parseInt(Uri.parse(url).getQueryParameter("br"));
            JSONObject[] newSongJson = getOneQualityFromSongId(blockedSongIdList, expectBitrate);
            for (int i = 0; i < originalSongArray.length(); i++) {
                if (originalSongArray.getJSONObject(i).isNull("url")) {
                    String returnUrl;
                    if (newSongJson[i].getLong("fid") == 0) {
                        returnUrl = generateUrlByDownload(expectBitrate, blockedSongIdList.get(i));
                    } else {
                        returnUrl = generateUrl(newSongJson[i].getLong("fid"));
                    }
                    originalSongArray.getJSONObject(i)
                            .put("url", returnUrl)
                            .put("br", newSongJson[i].getInt("br"))
                            .put("size", newSongJson[i].getString("size"))
                            .put("code", 200)
                            .put("type", "mp3")
                            .put("gain", newSongJson[i].getString("vd"));
                }
            }
            return originalJson.toString();
        } else
            return originalContent;
    }

    protected static String modifyDownloadApi(String url, String originalContent) throws JSONException, IllegalAccessException, InstantiationException, InvocationTargetException, MalformedURLException {
        JSONObject originalJson = new JSONObject(originalContent);
        JSONObject originalSong = originalJson.getJSONObject("data");

        int expectBitrate = Integer.parseInt(Uri.parse(url).getQueryParameter("br"));
        if (!originalSong.isNull("url")) {
            return originalContent;
        }
        if (originalSong.getInt("code") == -105 && !Main.OPEN) {
            return originalContent;
        }
        // 收费歌曲返回-105，下架歌曲只能下载128k
        if (originalSong.getInt("code") == -105
                || originalSong.getInt("br") != expectBitrate) {

            long songId = originalSong.getLong("id");
            List<Long> list = new ArrayList<>();
            list.add(songId);
            JSONObject newSong = getOneQualityFromSongId(list, expectBitrate)[0];
            String newMd5 = String.format(Locale.getDefault(), "%32d", newSong.getLong("fid")).replace(" ", "0"); // 使用fid避免重复
            String newUrl = generateUrl(newSong.getLong("fid"));
            originalSong
                    .put("br", newSong.getInt("br"))
                    .put("code", 200)
                    .put("fee", 0)
                    .put("gain", newSong.getDouble("vd"))
                    .put("md5", newMd5)
                    .put("size", newSong.getLong("size"))
                    .put("type", "mp3")
                    .put("url", newUrl);

            return originalJson.toString();
        }
        return originalContent;
    }

    private static JSONObject[] getOneQualityFromSongId(List<Long> songIds, int expectBitrate) throws JSONException, IllegalAccessException, InstantiationException, InvocationTargetException {
        JSONArray c = new JSONArray();
        for (long songId : songIds) {
            c.put(new JSONObject().put("id", songId).put("v", 0));
        }

        Map<String, String> map = new HashMap<>();
        map.put("c", c.toString());

        String page = (String) XposedHelpers.callMethod(XposedHelpers.callMethod(CONSTRUCTOR_i_f.newInstance("v3/song/detail", map), "c"), "i");
        JSONObject root = new JSONObject(page);

        return getOneQualityFromJson(root, expectBitrate);
    }

    private static JSONObject[] getOneQualityFromJson(JSONObject root, int expectBitrate) throws JSONException {
        JSONArray jsonArraySong = root.getJSONArray("songs");
        JSONObject[] returnObjects = new JSONObject[jsonArraySong.length()];

        for (int i = 0; i < jsonArraySong.length(); i++) {
            JSONObject songObject = (JSONObject) jsonArraySong.get(i);
            for (String quality : QUALITY_MAP.keySet()) {
                if (songObject.has(quality) && !songObject.isNull(quality)
                        && expectBitrate >= songObject.getJSONObject(quality).getInt("br")) {
                    returnObjects[i] = songObject.getJSONObject(quality);
                    break;
                }
            }
        }

        return returnObjects;
    }

    private static String generateUrl(long fid) {
        return (String) XposedHelpers.callStaticMethod(CLASS_utils_NeteaseMusicUtils, "a", fid);
    }

    private static String generateUrlByDownload(int br, long id) throws IllegalAccessException, InvocationTargetException, InstantiationException, JSONException {
        String page = (String) XposedHelpers.callMethod(XposedHelpers.callMethod(CONSTRUCTOR_i_f1.newInstance("song/enhance/download/url?br=" + br + "&id=" + id + "_0"), "c"), "i");
        JSONObject jsonObject = new JSONObject(page);
        return jsonObject.getJSONObject("data").getString("url");
    }

    protected static boolean setDnsServer(String server) {
        try {
            CN_DNS_RESOVLER = new SimpleResolver(server);
            NEED_TO_CLEAN_DNS_CACHE = true;
        } catch (UnknownHostException e) {
            return false;
        }
        return true;
    }

    protected static String getIpByHost(String domain) throws TextParseException {
        // caches mechanism built-in, just look it up
        Lookup lookup = new Lookup(domain, Type.A);
        lookup.setResolver(CN_DNS_RESOVLER);
        if (NEED_TO_CLEAN_DNS_CACHE) {
            lookup.setCache(null);
            NEED_TO_CLEAN_DNS_CACHE = false;
        }
        Record[] records = lookup.run();
        if (lookup.getResult() == Lookup.SUCCESSFUL) {
            // already random, just pick index 0
            return records[0].rdataToString();
        }
        return null;
    }

    public static String getPage(String urlString) throws IOException {
        URL url = new URL(urlString);
        HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();

        BufferedReader br = new BufferedReader(new InputStreamReader(urlConnection.getInputStream()));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) {
            sb.append(line).append("\n");
        }
        br.close();
        urlConnection.disconnect();
        return sb.toString();
    }


    public static String getIpByHostForDnsTest(String domain, String dns) throws TextParseException, UnknownHostException {
        Lookup lookup = new Lookup(domain, Type.A);
        lookup.setResolver(new SimpleResolver(dns));
        lookup.setCache(null);
        Record[] records = lookup.run();
        if (lookup.getResult() == Lookup.SUCCESSFUL) {
            return records[0].rdataToString();
        } else
            throw new UnknownHostException();
    }

    public static String getLastPartOfString(String path, String s) {
        int lastIndex = path.lastIndexOf(s);
        return path.substring(lastIndex + 1);
    }
}




