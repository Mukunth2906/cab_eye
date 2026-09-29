package com.cabeye.backend.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The database table behind every service, on a real (in-memory) H2 in PostgreSQL mode. */
class DatabaseStoreTest {

    public static class Row {
        public String name;
        public int visits;
        public Row() {}
        Row(String name, int visits) { this.name = name; this.visits = visits; }
    }

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    private JdbcTemplate freshDb() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:store-" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute(DataDirectory.SCHEMA);
        return jdbc;
    }

    @Test
    @DisplayName("rows survive: a second table object on the same database sees every write, in order")
    void writesArePersisted() {
        JdbcTemplate jdbc = freshDb();
        Table<Row> t = new JdbcTable<>(jdbc, "places", Row.class, mapper, null);
        t.put("a", new Row("PSG College", 1));
        t.put("b", new Row("Gandhipuram", 1));
        t.put("c", new Row("Ukkadam", 1));
        t.update("a", r -> { r.visits = 5; return r; });
        assertTrue(t.remove("c"));
        assertEquals(1, t.removeWhere(r -> r.name.equals("Gandhipuram")));
        t.put("d", new Row("Race Course", 2));

        // "Restart": a brand-new table object reading the same database.
        Table<Row> again = new JdbcTable<>(jdbc, "places", Row.class, mapper, null);
        List<Row> rows = again.all();
        assertEquals(2, rows.size());
        assertEquals("PSG College", rows.get(0).name, "insertion order kept");
        assertEquals(5, rows.get(0).visits, "update persisted");
        assertEquals("Race Course", rows.get(1).name);
        assertFalse(again.get("c").isPresent(), "remove persisted");
    }

    @Test
    @DisplayName("tables do not see each other's rows")
    void tablesAreSeparate() {
        JdbcTemplate jdbc = freshDb();
        new JdbcTable<>(jdbc, "one", Row.class, mapper, null).put("k", new Row("x", 1));
        assertEquals(0, new JdbcTable<>(jdbc, "two", Row.class, mapper, null).size());
    }

    @Test
    @DisplayName("an existing JSON file is imported once, then renamed")
    void importsLegacyJson(@TempDir Path dir) {
        JsonFileStore<Row> old = new JsonFileStore<>(dir, "accounts", Row.class, mapper);
        old.put("rider-1", new Row("Harshini", 3));
        old.put("rider-2", new Row("Asha", 1));

        JdbcTemplate jdbc = freshDb();
        Table<Row> t = new JdbcTable<>(jdbc, "accounts", Row.class, mapper, dir);
        assertEquals(2, t.size());
        assertEquals("Harshini", t.get("rider-1").orElseThrow().name);
        assertFalse(Files.exists(dir.resolve("accounts.json")));
        assertTrue(Files.exists(dir.resolve("accounts.json.imported")));

        // Not imported twice.
        Table<Row> again = new JdbcTable<>(jdbc, "accounts", Row.class, mapper, dir);
        assertEquals(2, again.size());
    }
}
