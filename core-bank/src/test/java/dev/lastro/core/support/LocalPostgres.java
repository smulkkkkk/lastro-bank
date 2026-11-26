package dev.lastro.core.support;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/** PostgreSQL real, sem Docker nem instalação. Só para testes e desenvolvimento. */
public final class LocalPostgres {

    public static final String ROLE = "lastro_core";
    public static final String SENTINEL_ROLE = "lastro_sentinel";
    /** Só para rodar no próprio computador; o iniciar.cmd passa o mesmo para o sentinel. */
    public static final String DEV_SENTINEL_SECRET = "segredo-local-do-lastro-2026";
    private static final String DATABASE = "lastro";

    private LocalPostgres() {
    }

    /** Banco descartável em porta livre. */
    public static EmbeddedPostgres temporary() {
        return start(EmbeddedPostgres.builder());
    }

    /**
     * Banco que sobrevive entre execuções. Fechar o terminal à força deixa o PostgreSQL rodando;
     * nesse caso, usa o que já está de pé.
     */
    public static String persistentUrl(Path directory, int port) {
        String url = "jdbc:postgresql://localhost:" + port + "/" + DATABASE + "?user=" + ROLE;
        try (Connection running = DriverManager.getConnection(url)) {
            prepareSentinel("jdbc:postgresql://localhost:" + port + "/" + DATABASE + "?user=postgres");
            return url;
        } catch (SQLException notRunning) {
            return url(start(EmbeddedPostgres.builder()
                    .setDataDirectory(directory)
                    .setCleanDataDirectory(false)
                    .setPort(port)));
        }
    }

    public static String url(EmbeddedPostgres postgres) {
        return postgres.getJdbcUrl(ROLE, DATABASE);
    }

    private static EmbeddedPostgres start(EmbeddedPostgres.Builder builder) {
        try {
            EmbeddedPostgres postgres = builder
                    .setServerConfig("shared_buffers", "64MB")
                    .setServerConfig("max_connections", "40")
                    .start();
            createDatabase(postgres);
            prepareSentinel(postgres.getJdbcUrl("postgres", DATABASE));
            return postgres;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * O sentinel tem usuário e schema próprios. O core-bank, dono do banco, não recebe acesso a eles,
     * e o sentinel não recebe acesso ao core.
     */
    private static void prepareSentinel(String superuserUrl) {
        try (Connection connection = DriverManager.getConnection(superuserUrl);
                Statement statement = connection.createStatement()) {
            if (!statement.executeQuery("SELECT 1 FROM pg_roles WHERE rolname = '" + SENTINEL_ROLE + "'").next()) {
                statement.execute("CREATE ROLE " + SENTINEL_ROLE + " LOGIN");
            }
            statement.execute("CREATE SCHEMA IF NOT EXISTS sentinel AUTHORIZATION " + SENTINEL_ROLE);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A aplicação roda com um usuário comum, como em produção, e não como superusuário. */
    private static void createDatabase(EmbeddedPostgres postgres) {
        try (Connection connection = postgres.getPostgresDatabase().getConnection();
                Statement statement = connection.createStatement()) {
            if (!statement.executeQuery("SELECT 1 FROM pg_roles WHERE rolname = '" + ROLE + "'").next()) {
                statement.execute("CREATE ROLE " + ROLE + " LOGIN");
            }
            if (!statement.executeQuery("SELECT 1 FROM pg_database WHERE datname = '" + DATABASE + "'").next()) {
                statement.execute("CREATE DATABASE " + DATABASE + " OWNER " + ROLE);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
