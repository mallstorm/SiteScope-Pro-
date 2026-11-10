package pro.sitescope.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteStatement;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Calendar;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class IpDataStore extends SQLiteOpenHelper {
    private static final String DB_NAME = "ip_intelligence.db";
    private static final int DB_VERSION = 1;
    private static final int IMPORT_BATCH = 5000;
    private static final Map<String,String> COUNTRY_NAMES = new LinkedHashMap<String,String>();

    public interface Progress {
        void onProgress(long rows, String stage);
        boolean isCancelled();
    }

    public static final class DatasetState {
        public String kind = "";
        public long rows;
        public String source = "";
        public String version = "";
        public String state = "";
        public String issue = "";
        public long importedAt;
        public long validatedAt;
        public boolean installed;
        public boolean valid;
        public boolean current;
        public boolean indexesValid;
        public boolean integrityValid = true;
        public boolean officialSource;
    }

    public static final class Status {
        public long geoRows;
        public long asnRows;
        public String citySource = "";
        public String asnSource = "";
        public DatasetState city = new DatasetState();
        public DatasetState asn = new DatasetState();
        public boolean overallValid;
        public boolean overallCurrent;
        public long lastValidatedAt;
    }

    public static final class GeoRange {
        public int family;
        public String startIp = "";
        public String endIp = "";
        public String continent = "";
        public String countryCode = "";
        public String country = "";
        public String region = "";
        public String city = "";
        public double latitude;
        public double longitude;
        public String asn = "";
        public String organization = "";
        public String cidrs() {
            try { return join(IpMath.rangeToCidrs(startIp, endIp), ", "); }
            catch (Exception error) { return ""; }
        }
    }

    public static final class AsnRange {
        public int family;
        public String startIp = "";
        public String endIp = "";
        public long asn;
        public String organization = "";
        public String cidrs() {
            try { return join(IpMath.rangeToCidrs(startIp, endIp), ", "); }
            catch (Exception error) { return ""; }
        }
    }

    public static final class ProviderSummary {
        public long asn;
        public String organization = "";
        public int sampledRanges;
    }

    public IpDataStore(Context context) {
        super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE geo_ranges (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "family INTEGER NOT NULL," +
            "start_key TEXT NOT NULL," +
            "end_key TEXT NOT NULL," +
            "start_ip TEXT NOT NULL," +
            "end_ip TEXT NOT NULL," +
            "continent TEXT," +
            "country_code TEXT," +
            "country TEXT," +
            "region TEXT," +
            "city TEXT," +
            "latitude REAL," +
            "longitude REAL)");
        db.execSQL("CREATE INDEX idx_geo_lookup ON geo_ranges(family,start_key,end_key)");
        db.execSQL("CREATE INDEX idx_geo_country ON geo_ranges(country_code COLLATE NOCASE,country COLLATE NOCASE)");
        db.execSQL("CREATE INDEX idx_geo_region ON geo_ranges(country_code COLLATE NOCASE,region COLLATE NOCASE)");
        db.execSQL("CREATE INDEX idx_geo_city ON geo_ranges(country_code COLLATE NOCASE,region COLLATE NOCASE,city COLLATE NOCASE)");

        db.execSQL("CREATE TABLE asn_ranges (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "family INTEGER NOT NULL," +
            "start_key TEXT NOT NULL," +
            "end_key TEXT NOT NULL," +
            "start_ip TEXT NOT NULL," +
            "end_ip TEXT NOT NULL," +
            "asn INTEGER NOT NULL," +
            "organization TEXT)");
        db.execSQL("CREATE INDEX idx_asn_lookup ON asn_ranges(family,start_key,end_key)");
        db.execSQL("CREATE INDEX idx_asn_number ON asn_ranges(asn)");
        db.execSQL("CREATE INDEX idx_asn_org ON asn_ranges(organization COLLATE NOCASE)");

        db.execSQL("CREATE TABLE metadata (name TEXT PRIMARY KEY,value TEXT)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS geo_ranges");
        db.execSQL("DROP TABLE IF EXISTS asn_ranges");
        db.execSQL("DROP TABLE IF EXISTS metadata");
        onCreate(db);
    }

    public Status status() {
        SQLiteDatabase db = getReadableDatabase();
        Status status = new Status();
        status.geoRows = metadataRows(db, "city", "geo_ranges");
        status.asnRows = metadataRows(db, "asn", "asn_ranges");
        status.citySource = metadata(db, "city_source");
        status.asnSource = metadata(db, "asn_source");
        status.city = state(db, true, status.geoRows, false);
        status.asn = state(db, false, status.asnRows, false);
        status.overallValid = status.city.valid && status.asn.valid;
        status.overallCurrent = status.overallValid && status.city.current && status.asn.current;
        status.lastValidatedAt = Math.min(nonZeroOrMax(status.city.validatedAt), nonZeroOrMax(status.asn.validatedAt));
        if (status.lastValidatedAt == Long.MAX_VALUE) status.lastValidatedAt = 0;
        return status;
    }

    public Status validate(boolean thorough) {
        SQLiteDatabase db = getWritableDatabase();
        boolean integrity = true;
        String integrityIssue = "";
        if (thorough) {
            Cursor check = db.rawQuery("PRAGMA quick_check(1)", null);
            try {
                integrity = check.moveToFirst() && "ok".equalsIgnoreCase(value(check.getString(0)));
                if (!integrity && check.moveToFirst()) integrityIssue = value(check.getString(0));
            } finally { check.close(); }
        }
        long geo = scalarLong(db, "SELECT COUNT(*) FROM geo_ranges");
        long asnRows = scalarLong(db, "SELECT COUNT(*) FROM asn_ranges");
        DatasetState city = validateDataset(db, true, geo, integrity, integrityIssue);
        DatasetState asn = validateDataset(db, false, asnRows, integrity, integrityIssue);
        Status status = new Status();
        status.geoRows = geo; status.asnRows = asnRows;
        status.citySource = city.source; status.asnSource = asn.source;
        status.city = city; status.asn = asn;
        status.overallValid = city.valid && asn.valid;
        status.overallCurrent = status.overallValid && city.current && asn.current;
        status.lastValidatedAt = Math.min(nonZeroOrMax(city.validatedAt), nonZeroOrMax(asn.validatedAt));
        if (status.lastValidatedAt == Long.MAX_VALUE) status.lastValidatedAt = 0;
        return status;
    }

    public void recoverSourceMetadata(boolean city, String source, long importedAt) {
        if (source == null || source.trim().length() == 0) return;
        SQLiteDatabase db = getWritableDatabase();
        String prefix = city ? "city" : "asn";
        long rows = scalarLong(db, city ? "SELECT COUNT(*) FROM geo_ranges" : "SELECT COUNT(*) FROM asn_ranges");
        if (rows <= 0 || metadata(db, prefix + "_source").length() > 0) return;
        setMetadata(db, prefix + "_source", source);
        setMetadata(db, prefix + "_rows", String.valueOf(rows));
        setMetadata(db, prefix + "_imported_at", String.valueOf(importedAt > 0 ? importedAt : System.currentTimeMillis()));
        setMetadata(db, prefix + "_state", "ready");
        setMetadata(db, prefix + "_issue", "");
    }

    public void clearCity() {
        SQLiteDatabase db = getWritableDatabase();
        db.delete("geo_ranges", null, null);
        clearMetadata(db, "city");
    }

    public void clearAsn() {
        SQLiteDatabase db = getWritableDatabase();
        db.delete("asn_ranges", null, null);
        clearMetadata(db, "asn");
    }

    public long importCity(InputStream input, String source, Progress progress) throws Exception {
        SQLiteDatabase db = getWritableDatabase();
        beginStagingImport(db, true, source);
        long rows = 0;
        BufferedReader reader = null;
        try {
            prepareGeoStaging(db);
            String sql = "INSERT INTO geo_ranges_staging(family,start_key,end_key,start_ip,end_ip,continent,country_code,country,region,city,latitude,longitude) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)";
            SQLiteStatement statement = db.compileStatement(sql);
            reader = new BufferedReader(new InputStreamReader(input, "UTF-8"), 128 * 1024);
            db.beginTransaction();
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (progress != null && progress.isCancelled()) throw new InterruptedException("Импорт остановлен");
                    List<String> fields = parseCsv(line);
                    if (fields.size() < 8) continue;
                    String startIp = clean(fields.get(0));
                    String endIp = clean(fields.get(1));
                    if (!isIp(startIp) || !isIp(endIp)) continue;
                    int family = IpMath.familyBits(startIp) == 128 ? 6 : 4;
                    String continent = clean(fields.get(2));
                    String countryCode = clean(fields.get(3));
                    String country = countryName(countryCode);
                    String region;
                    String cityName;
                    double lat;
                    double lon;
                    if (fields.size() >= 11) {
                        region = clean(fields.get(5)); cityName = clean(fields.get(7));
                        lat = number(fields.get(9)); lon = number(fields.get(10));
                    } else {
                        region = fields.size() > 4 ? clean(fields.get(4)) : "";
                        cityName = fields.size() > 5 ? clean(fields.get(5)) : "";
                        lat = fields.size() > 6 ? number(fields.get(6)) : 0;
                        lon = fields.size() > 7 ? number(fields.get(7)) : 0;
                    }
                    statement.clearBindings();
                    statement.bindLong(1, family); statement.bindString(2, IpMath.key(startIp)); statement.bindString(3, IpMath.key(endIp));
                    statement.bindString(4, startIp); statement.bindString(5, endIp); statement.bindString(6, continent);
                    statement.bindString(7, countryCode.toUpperCase(Locale.US)); statement.bindString(8, country);
                    statement.bindString(9, region); statement.bindString(10, cityName); statement.bindDouble(11, lat); statement.bindDouble(12, lon);
                    statement.executeInsert();
                    rows++;
                    if (rows % IMPORT_BATCH == 0) {
                        db.setTransactionSuccessful(); db.endTransaction();
                        if (progress != null) progress.onProgress(rows, "Импорт во временную базу");
                        db.beginTransaction();
                    }
                }
                db.setTransactionSuccessful();
            } finally { if (db.inTransaction()) db.endTransaction(); }
            validateStaging(db, true, rows);
            if (progress != null) progress.onProgress(rows, "Атомарная установка базы");
            replaceGeoFromStaging(db, source, rows);
            if (progress != null) progress.onProgress(rows, "Готово");
            return rows;
        } catch (Exception error) {
            failStagingImport(db, true, error);
            try { db.execSQL("DROP TABLE IF EXISTS geo_ranges_staging"); } catch (Exception ignored) { }
            throw error;
        } finally {
            if (reader != null) try { reader.close(); } catch (Exception ignored) { }
        }
    }

    public long importAsn(InputStream input, String source, Progress progress) throws Exception {
        SQLiteDatabase db = getWritableDatabase();
        beginStagingImport(db, false, source);
        long rows = 0;
        BufferedReader reader = null;
        try {
            prepareAsnStaging(db);
            String sql = "INSERT INTO asn_ranges_staging(family,start_key,end_key,start_ip,end_ip,asn,organization) VALUES(?,?,?,?,?,?,?)";
            SQLiteStatement statement = db.compileStatement(sql);
            reader = new BufferedReader(new InputStreamReader(input, "UTF-8"), 128 * 1024);
            db.beginTransaction();
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (progress != null && progress.isCancelled()) throw new InterruptedException("Импорт остановлен");
                    List<String> fields = parseCsv(line);
                    if (fields.size() < 4) continue;
                    String startIp = clean(fields.get(0)); String endIp = clean(fields.get(1));
                    if (!isIp(startIp) || !isIp(endIp)) continue;
                    long asn;
                    try { asn = Long.parseLong(clean(fields.get(2)).replace("AS", "").replace("as", "")); }
                    catch (Exception ignored) { continue; }
                    int family = IpMath.familyBits(startIp) == 128 ? 6 : 4;
                    statement.clearBindings();
                    statement.bindLong(1, family); statement.bindString(2, IpMath.key(startIp)); statement.bindString(3, IpMath.key(endIp));
                    statement.bindString(4, startIp); statement.bindString(5, endIp); statement.bindLong(6, asn); statement.bindString(7, clean(fields.get(3)));
                    statement.executeInsert();
                    rows++;
                    if (rows % IMPORT_BATCH == 0) {
                        db.setTransactionSuccessful(); db.endTransaction();
                        if (progress != null) progress.onProgress(rows, "Импорт во временную базу");
                        db.beginTransaction();
                    }
                }
                db.setTransactionSuccessful();
            } finally { if (db.inTransaction()) db.endTransaction(); }
            validateStaging(db, false, rows);
            if (progress != null) progress.onProgress(rows, "Атомарная установка базы");
            replaceAsnFromStaging(db, source, rows);
            if (progress != null) progress.onProgress(rows, "Готово");
            return rows;
        } catch (Exception error) {
            failStagingImport(db, false, error);
            try { db.execSQL("DROP TABLE IF EXISTS asn_ranges_staging"); } catch (Exception ignored) { }
            throw error;
        } finally {
            if (reader != null) try { reader.close(); } catch (Exception ignored) { }
        }
    }

    public GeoRange lookupGeo(String ip) {
        SQLiteDatabase db = getReadableDatabase();
        int family = IpMath.familyBits(ip) == 128 ? 6 : 4;
        String key = IpMath.key(ip);
        Cursor cursor = db.rawQuery("SELECT family,start_ip,end_ip,continent,country_code,country,region,city,latitude,longitude FROM geo_ranges WHERE family=? AND start_key<=? AND end_key>=? ORDER BY start_key DESC LIMIT 1",
            new String[]{String.valueOf(family), key, key});
        try {
            if (!cursor.moveToFirst()) return null;
            GeoRange item = geo(cursor);
            AsnRange asn = lookupAsn(ip);
            if (asn != null) { item.asn = "AS" + asn.asn; item.organization = asn.organization; }
            return item;
        } finally { cursor.close(); }
    }

    public AsnRange lookupAsn(String ip) {
        SQLiteDatabase db = getReadableDatabase();
        int family = IpMath.familyBits(ip) == 128 ? 6 : 4;
        String key = IpMath.key(ip);
        Cursor cursor = db.rawQuery("SELECT family,start_ip,end_ip,asn,organization FROM asn_ranges WHERE family=? AND start_key<=? AND end_key>=? ORDER BY start_key DESC LIMIT 1",
            new String[]{String.valueOf(family), key, key});
        try { return cursor.moveToFirst() ? asn(cursor) : null; }
        finally { cursor.close(); }
    }

    public List<GeoRange> searchGeo(String country, String region, String city, int limit, int offset) {
        return searchGeo("", country, region, city, limit, offset);
    }

    public List<GeoRange> searchGeo(String continent, String country, String region, String city, int limit, int offset) {
        SQLiteDatabase db = getReadableDatabase();
        StringBuilder where = new StringBuilder("1=1");
        List<String> args = new ArrayList<String>();
        if (!empty(continent)) { where.append(" AND continent COLLATE NOCASE = ?"); args.add(continent.trim()); }
        if (!empty(country)) {
            where.append(" AND (country_code COLLATE NOCASE = ? OR country COLLATE NOCASE LIKE ?)");
            args.add(country.trim()); args.add("%" + country.trim() + "%");
        }
        if (!empty(region)) { where.append(" AND region COLLATE NOCASE LIKE ?"); args.add("%" + region.trim() + "%"); }
        if (!empty(city)) { where.append(" AND city COLLATE NOCASE LIKE ?"); args.add("%" + city.trim() + "%"); }
        args.add(String.valueOf(Math.max(1, Math.min(limit, 2000))));
        args.add(String.valueOf(Math.max(0, offset)));
        Cursor cursor = db.rawQuery("SELECT family,start_ip,end_ip,continent,country_code,country,region,city,latitude,longitude FROM geo_ranges WHERE " + where + " ORDER BY country,region,city,start_key LIMIT ? OFFSET ?", args.toArray(new String[args.size()]));
        List<GeoRange> output = new ArrayList<GeoRange>();
        try {
            while (cursor.moveToNext()) {
                GeoRange item = geo(cursor);
                AsnRange provider = lookupAsn(item.startIp);
                if (provider != null) { item.asn = "AS" + provider.asn; item.organization = provider.organization; }
                output.add(item);
            }
        } finally { cursor.close(); }
        return output;
    }

    public List<AsnRange> searchAsn(String providerOrAsn, int limit, int offset) {
        SQLiteDatabase db = getReadableDatabase();
        String value = providerOrAsn == null ? "" : providerOrAsn.trim();
        String sql;
        String[] args;
        String normalized = value.toUpperCase(Locale.US).replace("AS", "").trim();
        if (normalized.matches("\\d+")) {
            sql = "SELECT family,start_ip,end_ip,asn,organization FROM asn_ranges WHERE asn=? ORDER BY family,start_key LIMIT ? OFFSET ?";
            args = new String[]{normalized, String.valueOf(Math.max(1, Math.min(limit, 5000))), String.valueOf(Math.max(0, offset))};
        } else {
            sql = "SELECT family,start_ip,end_ip,asn,organization FROM asn_ranges WHERE organization COLLATE NOCASE LIKE ? ORDER BY organization,family,start_key LIMIT ? OFFSET ?";
            args = new String[]{"%" + value + "%", String.valueOf(Math.max(1, Math.min(limit, 5000))), String.valueOf(Math.max(0, offset))};
        }
        Cursor cursor = db.rawQuery(sql, args);
        List<AsnRange> output = new ArrayList<AsnRange>();
        try { while (cursor.moveToNext()) output.add(asn(cursor)); }
        finally { cursor.close(); }
        return output;
    }

    public List<ProviderSummary> providersForGeo(String country, String region, String city, int sampleLimit) {
        return providersForGeo("", country, region, city, sampleLimit);
    }

    public List<ProviderSummary> providersForGeo(String continent, String country, String region, String city, int sampleLimit) {
        List<GeoRange> ranges = searchGeo(continent, country, region, city, Math.max(100, Math.min(sampleLimit, 3000)), 0);
        Map<Long, ProviderSummary> unique = new LinkedHashMap<Long, ProviderSummary>();
        for (GeoRange range : ranges) {
            AsnRange asn = lookupAsn(range.startIp);
            if (asn == null) continue;
            ProviderSummary item = unique.get(asn.asn);
            if (item == null) {
                item = new ProviderSummary(); item.asn = asn.asn; item.organization = asn.organization;
                unique.put(asn.asn, item);
            }
            item.sampledRanges++;
        }
        return new ArrayList<ProviderSummary>(unique.values());
    }

    public List<String> countries(int limit) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.rawQuery("SELECT DISTINCT country_code FROM geo_ranges WHERE country_code<>'' ORDER BY country_code LIMIT ?", new String[]{String.valueOf(Math.max(1, Math.min(limit, 1000)))});
        List<String> output = new ArrayList<String>();
        try {
            while (cursor.moveToNext()) {
                String code = value(cursor.getString(0));
                output.add(code + " — " + countryName(code));
            }
        } finally { cursor.close(); }
        return output;
    }

    public List<String> regions(String country, int limit) {
        if (empty(country)) return new ArrayList<String>();
        return distinct("SELECT DISTINCT region FROM geo_ranges WHERE region<>'' AND (country_code COLLATE NOCASE = ? OR country COLLATE NOCASE LIKE ?) ORDER BY region LIMIT ?", limit, country, "%" + country + "%");
    }

    public List<String> cities(String country, String region, int limit) {
        if (empty(country)) return new ArrayList<String>();
        SQLiteDatabase db = getReadableDatabase();
        String sql = "SELECT DISTINCT city FROM geo_ranges WHERE city<>'' AND (country_code COLLATE NOCASE = ? OR country COLLATE NOCASE LIKE ?)";
        List<String> args = new ArrayList<String>(); args.add(country); args.add("%" + country + "%");
        if (!empty(region)) { sql += " AND region COLLATE NOCASE LIKE ?"; args.add("%" + region + "%"); }
        sql += " ORDER BY city LIMIT ?"; args.add(String.valueOf(Math.max(1, Math.min(limit, 5000))));
        Cursor cursor = db.rawQuery(sql, args.toArray(new String[args.size()]));
        List<String> output = new ArrayList<String>();
        try { while (cursor.moveToNext()) output.add(cursor.getString(0)); }
        finally { cursor.close(); }
        return output;
    }

    private List<String> distinct(String sql, int limit, String... prefixArgs) {
        SQLiteDatabase db = getReadableDatabase();
        List<String> all = new ArrayList<String>();
        for (String value : prefixArgs) all.add(value);
        all.add(String.valueOf(Math.max(1, Math.min(limit, 5000))));
        Cursor cursor = db.rawQuery(sql, all.toArray(new String[all.size()]));
        List<String> output = new ArrayList<String>();
        try { while (cursor.moveToNext()) output.add(cursor.getString(0)); }
        finally { cursor.close(); }
        return output;
    }

    private DatasetState state(SQLiteDatabase db, boolean city, long rows, boolean thoroughIntegrity) {
        String prefix = city ? "city" : "asn";
        DatasetState item = new DatasetState();
        item.kind = city ? "City Lite" : "ASN Lite";
        item.rows = rows;
        item.source = metadata(db, prefix + "_source");
        item.version = sourceVersion(item.source);
        item.state = metadata(db, prefix + "_state");
        item.issue = metadata(db, prefix + "_issue");
        item.importedAt = parseLong(metadata(db, prefix + "_imported_at"));
        item.validatedAt = parseLong(metadata(db, prefix + "_validated_at"));
        item.installed = rows > 0;
        item.officialSource = isOfficialSource(item.source, city);
        item.indexesValid = indexesPresent(db, city);
        item.integrityValid = !thoroughIntegrity || quickCheck(db);
        boolean complete = rows > 0;
        if (item.officialSource) complete = city ? rows >= 1000000L : rows >= 100000L;
        boolean stateReady = item.state.length() == 0 || "ready".equals(item.state);
        item.valid = item.installed && complete && stateReady && item.indexesValid && item.integrityValid && sampleValid(db, city);
        item.current = item.valid && versionCurrent(item.version);
        if (!item.installed) item.issue = "База не установлена";
        else if (!complete) item.issue = "Импорт неполный: недостаточно записей";
        else if (!item.indexesValid) item.issue = "Отсутствуют обязательные индексы SQLite";
        else if (!item.integrityValid) item.issue = "SQLite quick_check завершился ошибкой";
        else if (!sampleValid(db, city)) item.issue = "Контрольная выборка содержит некорректные записи";
        else if (!stateReady && item.issue.length() == 0) item.issue = "Импорт не был завершён корректно";
        else if (item.valid) item.issue = "";
        return item;
    }

    private static long metadataRows(SQLiteDatabase db, String prefix, String table) {
        long cached = parseLong(metadata(db, prefix + "_rows"));
        return cached > 0 ? cached : scalarLong(db, "SELECT COUNT(*) FROM " + table);
    }

    private static void beginStagingImport(SQLiteDatabase db, boolean city, String source) {
        String prefix = city ? "city" : "asn";
        setMetadata(db, prefix + "_update_state", "importing");
        setMetadata(db, prefix + "_update_issue", "");
        setMetadata(db, prefix + "_candidate_source", source == null ? "" : source);
        setMetadata(db, prefix + "_import_started_at", String.valueOf(System.currentTimeMillis()));
    }

    private static void failStagingImport(SQLiteDatabase db, boolean city, Exception error) {
        String prefix = city ? "city" : "asn";
        setMetadata(db, prefix + "_update_state", "failed");
        setMetadata(db, prefix + "_update_issue", error == null ? "Импорт прерван" : value(error.getMessage()));
        setMetadata(db, prefix + "_candidate_source", "");
    }

    private static void prepareGeoStaging(SQLiteDatabase db) {
        db.execSQL("DROP TABLE IF EXISTS geo_ranges_staging");
        db.execSQL("CREATE TABLE geo_ranges_staging (id INTEGER PRIMARY KEY AUTOINCREMENT,family INTEGER NOT NULL,start_key TEXT NOT NULL,end_key TEXT NOT NULL,start_ip TEXT NOT NULL,end_ip TEXT NOT NULL,continent TEXT,country_code TEXT,country TEXT,region TEXT,city TEXT,latitude REAL,longitude REAL)");
    }

    private static void prepareAsnStaging(SQLiteDatabase db) {
        db.execSQL("DROP TABLE IF EXISTS asn_ranges_staging");
        db.execSQL("CREATE TABLE asn_ranges_staging (id INTEGER PRIMARY KEY AUTOINCREMENT,family INTEGER NOT NULL,start_key TEXT NOT NULL,end_key TEXT NOT NULL,start_ip TEXT NOT NULL,end_ip TEXT NOT NULL,asn INTEGER NOT NULL,organization TEXT)");
    }

    private static void validateStaging(SQLiteDatabase db, boolean city, long expectedRows) {
        String table = city ? "geo_ranges_staging" : "asn_ranges_staging";
        long actual = scalarLong(db, "SELECT COUNT(*) FROM " + table);
        if (actual <= 0 || actual != expectedRows) throw new IllegalStateException("Временная база неполна: " + actual + " из " + expectedRows);
        String sql = city
            ? "SELECT family,start_ip,end_ip,country_code FROM geo_ranges_staging ORDER BY id LIMIT 32"
            : "SELECT family,start_ip,end_ip,asn FROM asn_ranges_staging ORDER BY id LIMIT 32";
        Cursor cursor = db.rawQuery(sql, null);
        int seen = 0;
        try {
            while (cursor.moveToNext()) {
                seen++;
                int family = cursor.getInt(0); String start = value(cursor.getString(1)); String end = value(cursor.getString(2));
                if ((family != 4 && family != 6) || !isIp(start) || !isIp(end)) throw new IllegalStateException("Некорректная контрольная запись");
                try { if (IpMath.parseIp(start).compareTo(IpMath.parseIp(end)) > 0) throw new IllegalStateException("Начало диапазона больше конца"); }
                catch (IllegalStateException e) { throw e; } catch (Exception e) { throw new IllegalStateException("Некорректный IP в контрольной выборке"); }
                if (!city && cursor.getLong(3) <= 0) throw new IllegalStateException("Некорректный ASN");
            }
        } finally { cursor.close(); }
        if (seen == 0) throw new IllegalStateException("Временная база пуста");
    }

    private static void replaceGeoFromStaging(SQLiteDatabase db, String source, long rows) {
        db.beginTransaction();
        try {
            db.delete("geo_ranges", null, null);
            db.execSQL("INSERT INTO geo_ranges(family,start_key,end_key,start_ip,end_ip,continent,country_code,country,region,city,latitude,longitude) SELECT family,start_key,end_key,start_ip,end_ip,continent,country_code,country,region,city,latitude,longitude FROM geo_ranges_staging");
            finishImport(db, true, source, rows);
            setMetadata(db, "city_update_state", "ready"); setMetadata(db, "city_update_issue", "");
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        db.execSQL("DROP TABLE IF EXISTS geo_ranges_staging");
    }

    private static void replaceAsnFromStaging(SQLiteDatabase db, String source, long rows) {
        db.beginTransaction();
        try {
            db.delete("asn_ranges", null, null);
            db.execSQL("INSERT INTO asn_ranges(family,start_key,end_key,start_ip,end_ip,asn,organization) SELECT family,start_key,end_key,start_ip,end_ip,asn,organization FROM asn_ranges_staging");
            finishImport(db, false, source, rows);
            setMetadata(db, "asn_update_state", "ready"); setMetadata(db, "asn_update_issue", "");
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        db.execSQL("DROP TABLE IF EXISTS asn_ranges_staging");
    }

    private DatasetState validateDataset(SQLiteDatabase db, boolean city, long rows, boolean integrity, String integrityIssue) {
        String prefix = city ? "city" : "asn";
        DatasetState item = state(db, city, rows, false);
        item.integrityValid = integrity;
        if (!integrity) {
            item.valid = false;
            item.issue = integrityIssue.length() == 0 ? "Ошибка целостности SQLite" : integrityIssue;
        }
        long now = System.currentTimeMillis();
        item.validatedAt = now;
        setMetadata(db, prefix + "_validated_at", String.valueOf(now));
        setMetadata(db, prefix + "_rows", String.valueOf(rows));
        setMetadata(db, prefix + "_state", item.valid ? "ready" : "invalid");
        setMetadata(db, prefix + "_issue", item.issue);
        item.state = item.valid ? "ready" : "invalid";
        item.current = item.valid && versionCurrent(item.version);
        return item;
    }

    private static void beginImport(SQLiteDatabase db, boolean city, String source) {
        String prefix = city ? "city" : "asn";
        setMetadata(db, prefix + "_state", "importing");
        setMetadata(db, prefix + "_issue", "Импорт выполняется");
        setMetadata(db, prefix + "_candidate_source", source == null ? "" : source);
        setMetadata(db, prefix + "_import_started_at", String.valueOf(System.currentTimeMillis()));
    }

    private static void finishImport(SQLiteDatabase db, boolean city, String source, long rows) {
        String prefix = city ? "city" : "asn";
        long now = System.currentTimeMillis();
        setMetadata(db, prefix + "_source", source == null ? "" : source);
        setMetadata(db, prefix + "_rows", String.valueOf(rows));
        setMetadata(db, prefix + "_imported_at", String.valueOf(now));
        setMetadata(db, prefix + "_state", "ready");
        setMetadata(db, prefix + "_issue", "");
        setMetadata(db, prefix + "_candidate_source", "");
    }

    private static void failImport(SQLiteDatabase db, boolean city, long rows, Exception error) {
        String prefix = city ? "city" : "asn";
        setMetadata(db, prefix + "_rows", String.valueOf(rows));
        setMetadata(db, prefix + "_state", "invalid");
        setMetadata(db, prefix + "_issue", error == null ? "Импорт прерван" : value(error.getMessage()));
    }

    private static void clearMetadata(SQLiteDatabase db, String prefix) {
        db.delete("metadata", "name LIKE ?", new String[]{prefix + "_%"});
    }

    private static boolean indexesPresent(SQLiteDatabase db, boolean city) {
        String[] names = city
            ? new String[]{"idx_geo_lookup", "idx_geo_country", "idx_geo_region", "idx_geo_city"}
            : new String[]{"idx_asn_lookup", "idx_asn_number", "idx_asn_org"};
        for (String name : names) {
            Cursor cursor = db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='index' AND name=? LIMIT 1", new String[]{name});
            try { if (!cursor.moveToFirst()) return false; }
            finally { cursor.close(); }
        }
        return true;
    }

    private static boolean sampleValid(SQLiteDatabase db, boolean city) {
        String sql = city
            ? "SELECT family,start_ip,end_ip,country_code FROM geo_ranges ORDER BY id LIMIT 24"
            : "SELECT family,start_ip,end_ip,asn FROM asn_ranges ORDER BY id LIMIT 24";
        Cursor cursor = db.rawQuery(sql, null);
        int seen = 0;
        int geoCountries = 0;
        try {
            while (cursor.moveToNext()) {
                seen++;
                int family = cursor.getInt(0);
                String start = value(cursor.getString(1));
                String end = value(cursor.getString(2));
                if ((family != 4 && family != 6) || !isIp(start) || !isIp(end)) return false;
                try {
                    if (IpMath.familyBits(start) != IpMath.familyBits(end)) return false;
                    if (IpMath.parseIp(start).compareTo(IpMath.parseIp(end)) > 0) return false;
                } catch (Exception error) { return false; }
                if (city) {
                    String country = value(cursor.getString(3));
                    if (country.length() == 2) geoCountries++;
                    else if (country.length() > 0) return false;
                } else if (cursor.getLong(3) <= 0) return false;
            }
        } finally { cursor.close(); }
        return seen > 0 && (!city || geoCountries > 0);
    }

    private static boolean quickCheck(SQLiteDatabase db) {
        Cursor cursor = db.rawQuery("PRAGMA quick_check(1)", null);
        try { return cursor.moveToFirst() && "ok".equalsIgnoreCase(value(cursor.getString(0))); }
        finally { cursor.close(); }
    }

    public static String sourceVersion(String source) {
        if (source == null) return "";
        Matcher matcher = Pattern.compile("(20\\d{2})[-_.](0[1-9]|1[0-2])").matcher(source);
        return matcher.find() ? matcher.group(1) + "-" + matcher.group(2) : "";
    }

    public static boolean versionCurrent(String version) {
        if (version == null || !version.matches("20\\d{2}-(0[1-9]|1[0-2])")) return false;
        try {
            int year = Integer.parseInt(version.substring(0, 4));
            int month = Integer.parseInt(version.substring(5, 7));
            Calendar now = Calendar.getInstance();
            int delta = (now.get(Calendar.YEAR) - year) * 12 + ((now.get(Calendar.MONTH) + 1) - month);
            return delta >= 0 && delta <= 1;
        } catch (Exception ignored) { return false; }
    }

    private static boolean isOfficialSource(String source, boolean city) {
        String value = source == null ? "" : source.toLowerCase(Locale.US);
        return value.contains(city ? "dbip-city-lite" : "dbip-asn-lite");
    }

    private static long parseLong(String value) {
        try { return Long.parseLong(value == null ? "0" : value); }
        catch (Exception ignored) { return 0; }
    }

    private static long nonZeroOrMax(long value) { return value <= 0 ? Long.MAX_VALUE : value; }

    private static GeoRange geo(Cursor cursor) {
        GeoRange item = new GeoRange();
        item.family = cursor.getInt(0);
        item.startIp = value(cursor.getString(1));
        item.endIp = value(cursor.getString(2));
        item.continent = value(cursor.getString(3));
        item.countryCode = value(cursor.getString(4));
        item.country = value(cursor.getString(5));
        item.region = value(cursor.getString(6));
        item.city = value(cursor.getString(7));
        item.latitude = cursor.isNull(8) ? 0 : cursor.getDouble(8);
        item.longitude = cursor.isNull(9) ? 0 : cursor.getDouble(9);
        return item;
    }

    private static AsnRange asn(Cursor cursor) {
        AsnRange item = new AsnRange();
        item.family = cursor.getInt(0);
        item.startIp = value(cursor.getString(1));
        item.endIp = value(cursor.getString(2));
        item.asn = cursor.getLong(3);
        item.organization = value(cursor.getString(4));
        return item;
    }

    private static List<String> parseCsv(String line) {
        List<String> output = new ArrayList<String>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') { current.append('"'); i++; }
                else quoted = !quoted;
            } else if (c == ',' && !quoted) { output.add(current.toString()); current.setLength(0); }
            else current.append(c);
        }
        output.add(current.toString());
        return output;
    }

    public static List<String> parseCsvForTest(String line) { return parseCsv(line); }

    private static boolean isIp(String value) { return value != null && (value.contains(".") || value.contains(":")); }
    private static boolean empty(String value) { return value == null || value.trim().length() == 0; }
    private static String clean(String value) { return value == null ? "" : value.trim(); }
    private static String value(String value) { return value == null ? "" : value; }
    private static String countryName(String code) {
        if (code == null || code.trim().length() != 2) return clean(code);
        String normalized = code.trim().toUpperCase(Locale.US);
        synchronized (COUNTRY_NAMES) {
            String cached = COUNTRY_NAMES.get(normalized);
            if (cached != null) return cached;
            String name = new Locale("", normalized).getDisplayCountry(Locale.getDefault());
            if (name == null || name.length() == 0) name = normalized;
            COUNTRY_NAMES.put(normalized, name);
            return name;
        }
    }
    private static double number(String value) { try { return Double.parseDouble(clean(value)); } catch (Exception ignored) { return 0; } }

    private static void dropGeoIndexes(SQLiteDatabase db) {
        db.execSQL("DROP INDEX IF EXISTS idx_geo_lookup");
        db.execSQL("DROP INDEX IF EXISTS idx_geo_country");
        db.execSQL("DROP INDEX IF EXISTS idx_geo_region");
        db.execSQL("DROP INDEX IF EXISTS idx_geo_city");
    }

    private static void rebuildGeoIndexes(SQLiteDatabase db) {
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_geo_lookup ON geo_ranges(family,start_key,end_key)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_geo_country ON geo_ranges(country_code COLLATE NOCASE,country COLLATE NOCASE)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_geo_region ON geo_ranges(country_code COLLATE NOCASE,region COLLATE NOCASE)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_geo_city ON geo_ranges(country_code COLLATE NOCASE,region COLLATE NOCASE,city COLLATE NOCASE)");
    }

    private static void dropAsnIndexes(SQLiteDatabase db) {
        db.execSQL("DROP INDEX IF EXISTS idx_asn_lookup");
        db.execSQL("DROP INDEX IF EXISTS idx_asn_number");
        db.execSQL("DROP INDEX IF EXISTS idx_asn_org");
    }

    private static void rebuildAsnIndexes(SQLiteDatabase db) {
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_asn_lookup ON asn_ranges(family,start_key,end_key)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_asn_number ON asn_ranges(asn)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_asn_org ON asn_ranges(organization COLLATE NOCASE)");
    }

    private static long scalarLong(SQLiteDatabase db, String sql) {
        Cursor cursor = db.rawQuery(sql, null);
        try { return cursor.moveToFirst() ? cursor.getLong(0) : 0; }
        finally { cursor.close(); }
    }

    private static String metadata(SQLiteDatabase db, String name) {
        Cursor cursor = db.rawQuery("SELECT value FROM metadata WHERE name=?", new String[]{name});
        try { return cursor.moveToFirst() ? value(cursor.getString(0)) : ""; }
        finally { cursor.close(); }
    }

    private static void setMetadata(SQLiteDatabase db, String name, String value) {
        ContentValues values = new ContentValues();
        values.put("name", name); values.put("value", value == null ? "" : value);
        db.insertWithOnConflict("metadata", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    private static String join(List<String> values, String separator) {
        StringBuilder out = new StringBuilder();
        for (String value : values) { if (out.length() > 0) out.append(separator); out.append(value); }
        return out.toString();
    }
}
