package com.cabeye.backend.store;

import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.nio.file.Path;

/**
 * The database connection.
 *
 * <h2>On the laptop (default): H2</h2>
 * A small Java database that runs <em>inside</em> the backend — nothing to install or start.
 * Everything is kept in one file, {@code <data dir>/cabeye.mv.db} (normally
 * {@code backend\data\cabeye.mv.db}), until you delete it. It runs in PostgreSQL mode, so the
 * same SQL works unchanged on the server later.
 *
 * <p>{@code AUTO_SERVER=TRUE} lets a viewer (the built-in console at {@code /h2-console}, or
 * DBeaver) open the same file while the backend is running.
 *
 * <h2>When deployed: PostgreSQL</h2>
 * Set {@code CABEYE_DB_URL} (e.g. {@code jdbc:postgresql://localhost:5432/cabeye}),
 * {@code CABEYE_DB_USER} and {@code CABEYE_DB_PASSWORD}. Nothing else changes.
 */
@Configuration
public class DatabaseConfig {

    private static final Logger log = LoggerFactory.getLogger(DatabaseConfig.class);

    @Bean(destroyMethod = "close")
    public DataSource dataSource(@Value("${cabeye.data.dir:data}") String dataDir,
                                 @Value("${cabeye.db.url:}") String url,
                                 @Value("${cabeye.db.user:sa}") String user,
                                 @Value("${cabeye.db.password:}") String password,
                                 @Value("${cabeye.db.max-pool:10}") int maxPool,
                                 @Value("${cabeye.db.min-idle:2}") int minIdle,
                                 @Value("${cabeye.db.connection-timeout-ms:30000}") long connTimeout,
                                 @Value("${cabeye.db.idle-timeout-ms:600000}") long idleTimeout,
                                 @Value("${cabeye.db.max-lifetime-ms:1800000}") long maxLifetime,
                                 @Value("${cabeye.db.leak-threshold-ms:10000}") long leakThreshold) {
        String jdbcUrl = url == null || url.isBlank() ? h2Url(dataDir) : url.trim();

        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(jdbcUrl);
        ds.setUsername(user);
        ds.setPassword(password);
        ds.setMaximumPoolSize(maxPool);
        ds.setMinimumIdle(Math.min(minIdle, maxPool));
        ds.setConnectionTimeout(connTimeout);
        ds.setIdleTimeout(idleTimeout);
        ds.setMaxLifetime(maxLifetime);
        ds.setLeakDetectionThreshold(leakThreshold);
        ds.setPoolName("cabeye-db");

        if (jdbcUrl.startsWith("jdbc:h2:")) {
            log.info("DATABASE H2 file {}.mv.db — view it at http://localhost:8080/h2-console "
                    + "(JDBC URL: {} , user: {} , maxPool: {})", h2File(dataDir), jdbcUrl, user, maxPool);
        } else {
            log.info("DATABASE {} (maxPool: {}, minIdle: {})",
                    jdbcUrl.replaceAll("password=[^&;]*", "password=***"), maxPool, minIdle);
        }
        return ds;
    }

    @Bean
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    /** Absolute path, forward slashes: H2 refuses bare relative paths, and Windows backslashes are ambiguous. */
    static String h2File(String dataDir) {
        return Path.of(dataDir).toAbsolutePath().normalize().resolve("cabeye").toString().replace('\\', '/');
    }

    static String h2Url(String dataDir) {
        return "jdbc:h2:file:" + h2File(dataDir)
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;AUTO_SERVER=TRUE";
    }
}
