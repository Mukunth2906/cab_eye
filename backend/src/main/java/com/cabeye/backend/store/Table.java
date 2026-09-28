package com.cabeye.backend.store;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * One durable key-value table — the only storage API the services use.
 *
 * <p>Two implementations: {@link JdbcTable} (the database: H2 on the laptop, PostgreSQL when
 * deployed) and {@link JsonFileStore} (the original JSON files, kept for the one-time import).
 * Every write is persisted before it returns.
 */
public interface Table<T> {

    Optional<T> get(String key);

    List<T> all();

    List<T> where(Predicate<T> filter);

    Optional<T> first(Predicate<T> filter);

    int size();

    T put(String key, T row);

    /**
     * Read-modify-write under the table's lock.
     *
     * @return the row after {@code change}, or empty if the key does not exist
     */
    Optional<T> update(String key, UnaryOperator<T> change);

    boolean remove(String key);

    /** Removes every row matching {@code filter}; returns how many went. */
    int removeWhere(Predicate<T> filter);

    Collection<String> keys();
}
