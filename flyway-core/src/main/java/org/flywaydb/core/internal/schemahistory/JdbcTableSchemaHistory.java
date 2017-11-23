/*
 * Copyright 2010-2017 Boxfuse GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.flywaydb.core.internal.schemahistory;

import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationType;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.logging.Log;
import org.flywaydb.core.api.logging.LogFactory;
import org.flywaydb.core.api.resolver.ResolvedMigration;
import org.flywaydb.core.internal.database.Connection;
import org.flywaydb.core.internal.database.Database;
import org.flywaydb.core.internal.database.FlywaySqlException;
import org.flywaydb.core.internal.database.JdbcTemplate;
import org.flywaydb.core.internal.database.Schema;
import org.flywaydb.core.internal.database.SqlScript;
import org.flywaydb.core.internal.database.Table;
import org.flywaydb.core.internal.util.jdbc.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Supports reading and writing to the metadata table.
 */
public class JdbcTableSchemaHistory extends SchemaHistory {
    private static final Log LOG = LogFactory.getLog(JdbcTableSchemaHistory.class);

    /**
     * The database to use.
     */
    private final Database database;

    /**
     * The schema history table used by flyway.
     */
    private final Table table;

    /**
     * Connection with access to the database.
     */
    private final Connection<?> connection;

    private final JdbcTemplate jdbcTemplate;

    /**
     * Applied migration cache.
     */
    private final LinkedList<AppliedMigration> cache = new LinkedList<AppliedMigration>();

    /**
     * The user invoking Flyway, for audit purposes.
     */
    private String installedBy;

    /**
     * Creates a new instance of the metadata table support.
     *
     * @param database    The database to use.
     * @param table       The schema history table used by flyway.
     * @param installedBy The user invoking Flyway, for audit purposes.
     */
    JdbcTableSchemaHistory(Database database, Table table, String installedBy) {
        this.connection = database.getMainConnection();
        this.database = database;
        this.table = table;
        this.installedBy = installedBy;
        jdbcTemplate = connection.getJdbcTemplate();
    }

    @Override
    public void clearCache() {
        cache.clear();
    }

    @Override
    public boolean exists() {
        return table.exists();
    }

    /**
     * Creates the metatable if it doesn't exist, upgrades it if it does.
     */
    private void createIfNotExists() {
        int retries = 0;
        while (!table.exists()) {
            if (retries == 0) {
                LOG.info("Creating Schema History table: " + table);
            }

            try {
                new SqlScript(database.getCreateScript(table), database).execute(connection.getJdbcTemplate());
                LOG.debug("Schema History table " + table + " created.");
            } catch (FlywayException e) {
                if (++retries >= 10) {
                    throw e;
                }
                try {
                    LOG.debug("Schema History table creation failed. Retrying in 1 sec ...");
                    Thread.sleep(1000);
                } catch (InterruptedException e1) {
                    // Ignore
                }
            }
        }
    }

    @Override
    public <T> T lock(Callable<T> callable) {
        createIfNotExists();
        return connection.lock(table, callable);
    }

    @Override
    protected void doAddAppliedMigration(MigrationVersion version, String description, MigrationType type, String script, Integer checksum, int executionTime, boolean success) {
        connection.changeCurrentSchemaTo(table.getSchema());

        createIfNotExists();

        // Lock again for databases with no DDL transactions to prevent implicit commits from triggering deadlocks
        // in highly concurrent environments
        table.lock();

        try {
            String versionStr = version == null ? null : version.toString();
            int installedRank = type == MigrationType.SCHEMA ? 0 : calculateInstalledRank();

            jdbcTemplate.update(database.getInsertStatement(table),
                    installedRank, versionStr, description, type.name(), script, checksum, installedBy,
                    executionTime, success);

            LOG.debug("MetaData table " + table + " successfully updated to reflect changes");
        } catch (SQLException e) {
            throw new FlywaySqlException("Unable to insert row for version '" + version + "' in Schema History table " + table, e);
        }
    }

    /**
     * Calculates the installed rank for the new migration to be inserted.
     *
     * @return The installed rank.
     */
    private int calculateInstalledRank() throws SQLException {
        int currentMax = jdbcTemplate.queryForInt("SELECT MAX(" + database.quote("installed_rank") + ")"
                + " FROM " + table);
        return currentMax + 1;
    }

    @Override
    public List<AppliedMigration> allAppliedMigrations() {
        return findAppliedMigrations();
    }

    /**
     * Retrieve the applied migrations from the metadata table.
     *
     * @param migrationTypes The specific migration types to look for. (Optional) None means find all migrations.
     * @return The applied migrations.
     */
    private List<AppliedMigration> findAppliedMigrations(MigrationType... migrationTypes) {
        if (!table.exists()) {
            return new ArrayList<AppliedMigration>();
        }

        createIfNotExists();

        int minInstalledRank = cache.isEmpty() ? -1 : cache.getLast().getInstalledRank();

        String query = "SELECT " + database.quote("installed_rank")
                + "," + database.quote("version")
                + "," + database.quote("description")
                + "," + database.quote("type")
                + "," + database.quote("script")
                + "," + database.quote("checksum")
                + "," + database.quote("installed_on")
                + "," + database.quote("installed_by")
                + "," + database.quote("execution_time")
                + "," + database.quote("success")
                + " FROM " + table
                + " WHERE " + database.quote("installed_rank") + " > " + minInstalledRank;

        if (migrationTypes.length > 0) {
            query += " AND " + database.quote("type") + " IN (";
            StringBuilder queryBuilder = new StringBuilder(query);
            for (int i = 0; i < migrationTypes.length; i++) {
                if (i > 0) {
                    queryBuilder.append(",");
                }
                queryBuilder.append("'").append(migrationTypes[i]).append("'");
            }
            query = queryBuilder.toString();
            query += ")";
        }

        query += " ORDER BY " + database.quote("installed_rank");

        try {
            cache.addAll(jdbcTemplate.query(query, new RowMapper<AppliedMigration>() {
                public AppliedMigration mapRow(final ResultSet rs) throws SQLException {
                    Integer checksum = rs.getInt("checksum");
                    if (rs.wasNull()) {
                        checksum = null;
                    }

                    return new AppliedMigration(
                            rs.getInt("installed_rank"),
                            rs.getString("version") != null ? MigrationVersion.fromVersion(rs.getString("version")) : null,
                            rs.getString("description"),
                            MigrationType.valueOf(rs.getString("type")),
                            rs.getString("script"),
                            checksum,
                            rs.getTimestamp("installed_on"),
                            rs.getString("installed_by"),
                            rs.getInt("execution_time"),
                            rs.getBoolean("success")
                    );
                }
            }));
            return cache;
        } catch (SQLException e) {
            throw new FlywaySqlException("Error while retrieving the list of applied migrations from Schema History table "
                    + table, e);
        }
    }

    @Override
    public void removeFailedMigrations() {
        if (!table.exists()) {
            LOG.info("Repair of failed migration in Schema History table " + table + " not necessary. No failed migration detected.");
            return;
        }

        createIfNotExists();

        try {
            int failedCount = jdbcTemplate.queryForInt("SELECT COUNT(*) FROM " + table
                    + " WHERE " + database.quote("success") + "=" + database.getBooleanFalse());
            if (failedCount == 0) {
                LOG.info("Repair of failed migration in Schema History table " + table + " not necessary. No failed migration detected.");
                return;
            }
        } catch (SQLException e) {
            throw new FlywaySqlException("Unable to check the Schema History table " + table + " for failed migrations", e);
        }

        try {
            jdbcTemplate.execute("DELETE FROM " + table
                    + " WHERE " + database.quote("success") + " = " + database.getBooleanFalse());
        } catch (SQLException e) {
            throw new FlywaySqlException("Unable to repair Schema History table " + table, e);
        }
    }

    @Override
    public void addSchemasMarker(final Schema[] schemas) {
        createIfNotExists();

        // Lock again for databases with no DDL transactions to prevent implicit commits from triggering deadlocks
        // in highly concurrent environments
        table.lock();

        doAddSchemasMarker(schemas);
    }

    @Override
    public boolean hasSchemasMarker() {
        if (!table.exists()) {
            return false;
        }

        createIfNotExists();

        try {
            int count = jdbcTemplate.queryForInt(
                    "SELECT COUNT(*) FROM " + table + " WHERE " + database.quote("type") + "='SCHEMA'");
            return count > 0;
        } catch (SQLException e) {
            throw new FlywaySqlException("Unable to check whether the Schema History table " + table + " has a schema marker migration", e);
        }
    }

    @Override
    public boolean hasBaselineMarker() {
        if (!table.exists()) {
            return false;
        }

        createIfNotExists();

        try {
            int count = jdbcTemplate.queryForInt(
                    "SELECT COUNT(*) FROM " + table + " WHERE " + database.quote("type") + "='INIT' OR " + database.quote("type") + "='BASELINE'");
            return count > 0;
        } catch (SQLException e) {
            throw new FlywaySqlException("Unable to check whether the Schema History table " + table + " has an baseline marker migration", e);
        }
    }

    @Override
    public AppliedMigration getBaselineMarker() {
        List<AppliedMigration> appliedMigrations = findAppliedMigrations(MigrationType.BASELINE);
        return appliedMigrations.isEmpty() ? null : appliedMigrations.get(0);
    }

    @Override
    public boolean hasAppliedMigrations() {
        if (!table.exists()) {
            return false;
        }

        createIfNotExists();

        try {
            int count = jdbcTemplate.queryForInt(
                    "SELECT COUNT(*) FROM " + table + " WHERE " + database.quote("type") + " NOT IN ('SCHEMA', 'INIT', 'BASELINE')");
            return count > 0;
        } catch (SQLException e) {
            throw new FlywaySqlException("Unable to check whether the Schema History table " + table + " has applied migrations", e);
        }
    }

    @Override
    public void update(AppliedMigration appliedMigration, ResolvedMigration resolvedMigration) {
        clearCache();

        MigrationVersion version = appliedMigration.getVersion();

        String description = resolvedMigration.getDescription();
        Integer checksum = resolvedMigration.getChecksum();
        MigrationType type = appliedMigration.getType().isSynthetic()
                ? appliedMigration.getType()
                : resolvedMigration.getType();

        LOG.info("Repairing Schema History table for version " + version
                + " (Description: " + description + ", Type: " + type + ", Checksum: " + checksum + ")  ...");

        try {
            jdbcTemplate.update("UPDATE " + table
                            + " SET "
                            + database.quote("description") + "=? , "
                            + database.quote("type") + "=? , "
                            + database.quote("checksum") + "=?"
                            + " WHERE " + database.quote("version") + "=?",
                    description, type, checksum, version);
        } catch (SQLException e) {
            throw new FlywaySqlException("Unable to repair Schema History table " + table
                    + " for version " + version, e);
        }
    }

    @Override
    public String toString() {
        return table.toString();
    }
}
