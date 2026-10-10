package com.cabeye.backend.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * A {@link Table} stored in the database — H2 on the laptop, PostgreSQL when deployed.
 *
 * <p>All tables share one SQL table, {@code store_rows(table_name, row_key, ord, body, updated_at)},
 * with each row's object kept as JSON text in {@code body}. That keeps the SQL identical on H2
 * and PostgreSQL and lets every service keep its existing model classes unchanged.
 *
 * <p>Rows are also cached in memory (loaded once at start), so reads cost nothing; every write
 * goes to the database first and only then to the cache, so the cache can never hold something
 * the database lost.
 *
 * <p><b>One-time import:</b> if the table has no rows in the database and the old
 * {@code data/<name>.json} file exists, its rows are copied in and the file is renamed
 * {@code <name>.json.imported}. Existing accounts, places, trips and feedback carry over.
 */
public final class JdbcTable<T> implements Table<T> {

    private static final Logger log = LoggerFactory.getLogger(JdbcTable.class);

    private final JdbcTemplate jdbc;
    private final String name;
    private final Class<T> rowType;
    private final ObjectMapper mapper;
    private final Map<String, T> rows = new LinkedHashMap<>();
    private long nextOrd = 1;

    public JdbcTable(JdbcTemplate jdbc, String name, Class<T> rowType, ObjectMapper mapper, Path legacyJsonDir) {
        this.jdbc = jdbc;
        this.name = name;
        this.rowType = rowType;
        this.mapper = mapper;
        load();
        if (rows.isEmpty() && legacyJsonDir != null) importLegacy(legacyJsonDir);
    }

    // -----------------------------------------------------------------------------------
    //  Reads — querying DB with memory synchronization for multi-instance consistency
    // -----------------------------------------------------------------------------------

    @Override
    public synchronized Optional<T> get(String key) {
        if (key == null) return Optional.empty();
        List<String> found = jdbc.query(
                "SELECT body FROM store_rows WHERE table_name = ? AND row_key = ?",
                (rs, i) -> rs.getString(1),
                name, key);
        if (found.isEmpty()) {
            rows.remove(key);
            return Optional.empty();
        }
        try {
            T row = mapper.readValue(found.get(0), rowType);
            rows.put(key, row);
            return Optional.of(row);
        } catch (JsonProcessingException e) {
            log.error("Cannot deserialize row '{}' of table '{}'", key, name, e);
            return Optional.ofNullable(rows.get(key));
        }
    }

    @Override
    public synchronized List<T> all() {
        List<Object[]> found = jdbc.query(
                "SELECT row_key, body FROM store_rows WHERE table_name = ? ORDER BY ord",
                (rs, i) -> new Object[]{rs.getString(1), rs.getString(2)},
                name);
        List<T> out = new ArrayList<>();
        for (Object[] r : found) {
            String key = (String) r[0];
            try {
                T item = mapper.readValue((String) r[1], rowType);
                rows.put(key, item);
                out.add(item);
            } catch (JsonProcessingException e) {
                log.warn("Cannot deserialize row '{}' of table '{}'", key, name, e);
            }
        }
        return out;
    }

    @Override
    public synchronized List<T> where(Predicate<T> filter) {
        List<T> out = new ArrayList<>();
        for (T row : all()) {
            if (filter.test(row)) out.add(row);
        }
        return out;
    }

    @Override
    public synchronized Optional<T> first(Predicate<T> filter) {
        for (T row : all()) {
            if (filter.test(row)) return Optional.of(row);
        }
        return Optional.empty();
    }

    @Override
    public synchronized int size() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM store_rows WHERE table_name = ?",
                Integer.class, name);
        return count == null ? rows.size() : count;
    }

    @Override
    public synchronized Collection<String> keys() {
        return jdbc.query(
                "SELECT row_key FROM store_rows WHERE table_name = ? ORDER BY ord",
                (rs, i) -> rs.getString(1),
                name);
    }

    // -----------------------------------------------------------------------------------
    //  Writes — database first, then local cache
    // -----------------------------------------------------------------------------------

    @Override
    public synchronized T put(String key, T row) {
        write(key, row);
        rows.put(key, row);
        return row;
    }

    @Override
    public synchronized Optional<T> update(String key, UnaryOperator<T> change) {
        Optional<T> current = get(key);
        if (current.isEmpty()) return Optional.empty();
        T next = change.apply(current.get());
        write(key, next);
        rows.put(key, next);
        return Optional.of(next);
    }

    @Override
    public synchronized boolean remove(String key) {
        int deleted = jdbc.update("DELETE FROM store_rows WHERE table_name = ? AND row_key = ?", name, key);
        rows.remove(key);
        return deleted > 0;
    }

    @Override
    public synchronized int removeWhere(Predicate<T> filter) {
        List<String> doomed = new ArrayList<>();
        for (T item : all()) {
            if (filter.test(item)) {
                // find row_key from rows cache
                for (Map.Entry<String, T> entry : rows.entrySet()) {
                    if (entry.getValue() == item) doomed.add(entry.getKey());
                }
            }
        }
        for (String key : doomed) {
            jdbc.update("DELETE FROM store_rows WHERE table_name = ? AND row_key = ?", name, key);
            rows.remove(key);
        }
        return doomed.size();
    }

    // -----------------------------------------------------------------------------------
    //  Database
    // -----------------------------------------------------------------------------------

    private void write(String key, T row) {
        String body = toJson(row);
        Timestamp now = Timestamp.from(Instant.now());
        int updated = jdbc.update(
                "UPDATE store_rows SET body = ?, updated_at = ? WHERE table_name = ? AND row_key = ?",
                body, now, name, key);
        if (updated == 0) {
            Long maxOrd = jdbc.queryForObject(
                    "SELECT COALESCE(MAX(ord), 0) FROM store_rows WHERE table_name = ?",
                    Long.class, name);
            long ord = (maxOrd == null ? 0 : maxOrd) + 1;
            jdbc.update(
                    "INSERT INTO store_rows (table_name, row_key, ord, body, updated_at) VALUES (?, ?, ?, ?, ?)",
                    name, key, ord, body, now);
        }
    }

    private void load() {
        List<Object[]> found = jdbc.query(
                "SELECT row_key, body, ord FROM store_rows WHERE table_name = ? ORDER BY ord",
                (rs, i) -> new Object[]{rs.getString(1), rs.getString(2), rs.getLong(3)},
                name);
        for (Object[] r : found) {
            String key = (String) r[0];
            try {
                rows.put(key, mapper.readValue((String) r[1], rowType));
            } catch (JsonProcessingException e) {
                // Refuse to start rather than silently drop a row: a missing account row would
                // look to its owner like their account had been deleted.
                throw new IllegalStateException("Cannot read row '" + key + "' of table '" + name + "'", e);
            }
            nextOrd = Math.max(nextOrd, (Long) r[2] + 1);
        }
        log.info("DB table {} — {} row(s)", name, rows.size());
    }

    private void importLegacy(Path dir) {
        Path file = dir.resolve(name + ".json");
        if (!Files.exists(file)) return;
        Map<String, T> legacy = JsonFileStore.read(file, rowType, mapper);
        for (Map.Entry<String, T> e : legacy.entrySet()) {
            write(e.getKey(), e.getValue());
            rows.put(e.getKey(), e.getValue());
        }
        try {
            Files.move(file, file.resolveSibling(name + ".json.imported"), StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            log.warn("Imported {} but could not rename it: {}", file, e.toString());
        }
        log.info("DB table {} — imported {} row(s) from {} (renamed to .imported)", name, legacy.size(), file);
    }

    private String toJson(T row) {
        try {
            return mapper.writeValueAsString(row);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot store a row of table '" + name + "'", e);
        }
    }
}
