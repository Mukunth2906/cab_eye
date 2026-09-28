package com.cabeye.backend.store;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * A tiny durable key-value table: one JSON file per table, rewritten atomically on every change.
 *
 * <p><b>Now superseded by {@link JdbcTable}</b> (H2 / PostgreSQL). Kept for the one-time import of
 * existing JSON files and as a dependency-free fallback.
 *
 * <p>Why not JPA + Postgres originally: accounts, sessions and ride memory have to survive a backend
 * restart <i>now</i>, and this needs zero new dependencies — Jackson already ships with
 * {@code spring-boot-starter-web}. Every service talks to its table through this one class, so
 * swapping it for a Spring Data repository later touches one constructor per service, not the
 * logic.
 *
 * <p>Writes go to {@code name.json.tmp} first and are then moved over the real file, so a crash
 * mid-write leaves the previous good file rather than half a JSON document.
 *
 * <p>Sized for an MVP: the whole table is held in memory and the whole file rewritten on each
 * change. That is fine for hundreds of riders; it is the first thing to replace at thousands.
 */
public final class JsonFileStore<T> implements Table<T> {

    private static final Logger log = LoggerFactory.getLogger(JsonFileStore.class);

    private final Path file;
    private final ObjectMapper mapper;
    private final JavaType mapType;
    private final Map<String, T> rows = new LinkedHashMap<>();

    public JsonFileStore(Path dataDir, String name, Class<T> rowType, ObjectMapper mapper) {
        this.file = dataDir.resolve(name + ".json");
        this.mapper = mapper;
        this.mapType = mapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, rowType);
        load();
    }

    // -----------------------------------------------------------------------------------
    //  Reads
    // -----------------------------------------------------------------------------------

    @Override
    public synchronized Optional<T> get(String key) {
        return Optional.ofNullable(rows.get(key));
    }

    @Override
    public synchronized List<T> all() {
        return new ArrayList<>(rows.values());
    }

    @Override
    public synchronized List<T> where(Predicate<T> filter) {
        List<T> out = new ArrayList<>();
        for (T row : rows.values()) {
            if (filter.test(row)) out.add(row);
        }
        return out;
    }

    @Override
    public synchronized Optional<T> first(Predicate<T> filter) {
        for (T row : rows.values()) {
            if (filter.test(row)) return Optional.of(row);
        }
        return Optional.empty();
    }

    @Override
    public synchronized int size() {
        return rows.size();
    }

    // -----------------------------------------------------------------------------------
    //  Writes — every one is persisted before it returns
    // -----------------------------------------------------------------------------------

    @Override
    public synchronized T put(String key, T row) {
        rows.put(key, row);
        save();
        return row;
    }

    /**
     * Read-modify-write under the table lock, so two requests updating the same rider cannot
     * interleave and lose one another's change.
     *
     * @return the row after {@code change}, or empty if the key does not exist
     */
    @Override
    public synchronized Optional<T> update(String key, UnaryOperator<T> change) {
        T current = rows.get(key);
        if (current == null) return Optional.empty();
        T next = change.apply(current);
        rows.put(key, next);
        save();
        return Optional.of(next);
    }

    @Override
    public synchronized boolean remove(String key) {
        boolean removed = rows.remove(key) != null;
        if (removed) save();
        return removed;
    }

    /** Removes every row matching {@code filter}; returns how many went. */
    @Override
    public synchronized int removeWhere(Predicate<T> filter) {
        int before = rows.size();
        rows.values().removeIf(filter);
        int removed = before - rows.size();
        if (removed > 0) save();
        return removed;
    }

    @Override
    public synchronized Collection<String> keys() {
        return new ArrayList<>(rows.keySet());
    }

    // -----------------------------------------------------------------------------------
    //  Disk
    // -----------------------------------------------------------------------------------

    /** Reads one table file as-is; used for the one-time import into the database. */
    static <R> Map<String, R> read(Path file, Class<R> rowType, ObjectMapper mapper) {
        try {
            JavaType type = mapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, rowType);
            Map<String, R> loaded = mapper.readValue(file.toFile(), type);
            return loaded == null ? new LinkedHashMap<>() : loaded;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + file.toAbsolutePath()
                    + " for import — fix or move the file and restart", e);
        }
    }

    private void load() {
        if (!Files.exists(file)) {
            log.info("STORE {} — new, will be created on first write", file.toAbsolutePath());
            return;
        }
        try {
            Map<String, T> loaded = mapper.readValue(file.toFile(), mapType);
            if (loaded != null) rows.putAll(loaded);
            log.info("STORE {} — loaded {} row(s)", file.toAbsolutePath(), rows.size());
        } catch (IOException e) {
            // Refuse to start on a corrupt table rather than silently overwrite it with an
            // empty one on the next write — that would erase every account.
            throw new IllegalStateException("Cannot read " + file.toAbsolutePath()
                    + " — fix or move the file and restart", e);
        }
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), rows);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot write " + file.toAbsolutePath(), e);
        }
    }
}
