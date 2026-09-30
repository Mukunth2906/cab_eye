package com.cabeye.backend.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * The one place that builds the backend's durable tables.
 *
 * <p>Tables live in the database (see {@link DatabaseConfig}: H2 on the laptop, PostgreSQL when
 * deployed). {@code cabeye.data.dir} — {@code backend\data} when started with
 * {@code run-backend.ps1} — holds the H2 file, and is also where any old {@code *.json} tables
 * are picked up from for the one-time import. Tests point it at a throwaway folder.
 */
@Component
public class DataDirectory {

    /**
     * The single SQL table behind every {@link JdbcTable}. Plain types that mean the same on
     * H2 and PostgreSQL; created on start if missing, so there is no separate setup step.
     */
    static final String SCHEMA = """
            CREATE TABLE IF NOT EXISTS store_rows (
                table_name  VARCHAR(64)  NOT NULL,
                row_key     VARCHAR(200) NOT NULL,
                ord         BIGINT       NOT NULL,
                body        TEXT         NOT NULL,
                updated_at  TIMESTAMP    NOT NULL,
                PRIMARY KEY (table_name, row_key)
            )""";

    static final String INDEX_ORD = """
            CREATE INDEX IF NOT EXISTS idx_store_rows_ord ON store_rows (table_name, ord)
            """;

    private final Path root;
    private final ObjectMapper mapper;
    private final JdbcTemplate jdbc;

    public DataDirectory(@Value("${cabeye.data.dir:data}") String dir, ObjectMapper mapper, JdbcTemplate jdbc) {
        this.root = Path.of(dir).toAbsolutePath();
        this.mapper = mapper;
        this.jdbc = jdbc;
        try {
            jdbc.execute(SCHEMA);
            jdbc.execute(INDEX_ORD);
        } catch (Exception e) {
            // In concurrent multi-instance boot, table/index may already exist or be created by peer
            org.slf4j.LoggerFactory.getLogger(DataDirectory.class)
                    .debug("Schema initialization notice: {}", e.getMessage());
        }
    }

    public <T> Table<T> table(String name, Class<T> rowType) {
        return new JdbcTable<>(jdbc, name, rowType, mapper, root);
    }

    public Path root() {
        return root;
    }
}
