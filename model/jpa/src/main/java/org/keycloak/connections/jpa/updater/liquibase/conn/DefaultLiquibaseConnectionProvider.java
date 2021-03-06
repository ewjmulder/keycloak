/*
 * Copyright 2016 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.keycloak.connections.jpa.updater.liquibase.conn;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.jboss.logging.Logger;
import org.keycloak.Config;
import org.keycloak.connections.jpa.entityprovider.JpaEntityProvider;
import org.keycloak.connections.jpa.entityprovider.ProxyClassLoader;
import org.keycloak.connections.jpa.updater.liquibase.LiquibaseJpaUpdaterProvider;
import org.keycloak.connections.jpa.updater.liquibase.PostgresPlusDatabase;
import org.keycloak.connections.jpa.updater.liquibase.lock.CustomInsertLockRecordGenerator;
import org.keycloak.connections.jpa.updater.liquibase.lock.CustomLockDatabaseChangeLogGenerator;
import org.keycloak.connections.jpa.updater.liquibase.lock.DummyLockService;
import org.keycloak.connections.jpa.util.JpaUtils;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;

import liquibase.Liquibase;
import liquibase.changelog.ChangeLogParameters;
import liquibase.changelog.ChangeSet;
import liquibase.changelog.DatabaseChangeLog;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.core.DB2Database;
import liquibase.database.jvm.JdbcConnection;
import liquibase.exception.LiquibaseException;
import liquibase.logging.LogFactory;
import liquibase.logging.LogLevel;
import liquibase.parser.ChangeLogParser;
import liquibase.parser.ChangeLogParserFactory;
import liquibase.resource.ClassLoaderResourceAccessor;
import liquibase.resource.ResourceAccessor;
import liquibase.servicelocator.ServiceLocator;
import liquibase.sqlgenerator.SqlGeneratorFactory;

/**
 * @author <a href="mailto:mposolda@redhat.com">Marek Posolda</a>
 */
public class DefaultLiquibaseConnectionProvider implements LiquibaseConnectionProviderFactory, LiquibaseConnectionProvider {

    private static final Logger logger = Logger.getLogger(DefaultLiquibaseConnectionProvider.class);

    private volatile boolean initialized = false;

    private KeycloakSession keycloakSession;
    
    @Override
    public LiquibaseConnectionProvider create(KeycloakSession session) {
    	this.keycloakSession = session;
        if (!initialized) {
            synchronized (this) {
                if (!initialized) {
                    baseLiquibaseInitialization();
                    initialized = true;
                }
            }
        }
        return this;
    }

    protected void baseLiquibaseInitialization() {
        ServiceLocator sl = ServiceLocator.getInstance();
        sl.setResourceAccessor(new ClassLoaderResourceAccessor(getClass().getClassLoader()));

        if (!System.getProperties().containsKey("liquibase.scan.packages")) {
            if (sl.getPackages().remove("liquibase.core")) {
                sl.addPackageToScan("liquibase.core.xml");
            }

            if (sl.getPackages().remove("liquibase.parser")) {
                sl.addPackageToScan("liquibase.parser.core.xml");
            }

            if (sl.getPackages().remove("liquibase.serializer")) {
                sl.addPackageToScan("liquibase.serializer.core.xml");
            }

            sl.getPackages().remove("liquibase.ext");
            sl.getPackages().remove("liquibase.sdk");

            String lockPackageName = DummyLockService.class.getPackage().getName();
            logger.debugf("Added package %s to liquibase", lockPackageName);
            sl.addPackageToScan(lockPackageName);
        }

        LogFactory.setInstance(new LogWrapper());

        // Adding PostgresPlus support to liquibase
        DatabaseFactory.getInstance().register(new PostgresPlusDatabase());

        // Change command for creating lock and drop DELETE lock record from it
        SqlGeneratorFactory.getInstance().register(new CustomInsertLockRecordGenerator());

        // Use "SELECT FOR UPDATE" for locking database
        SqlGeneratorFactory.getInstance().register(new CustomLockDatabaseChangeLogGenerator());
    }


    @Override
    public void init(Config.Scope config) {

    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {

    }

    @Override
    public void close() {
    }

    @Override
    public String getId() {
        return "default";
    }

    @Override
    public Liquibase getLiquibase(Connection connection, String defaultSchema) throws LiquibaseException {
        Database database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
        if (defaultSchema != null) {
            database.setDefaultSchemaName(defaultSchema);
        }

        String changelog = getChangelogLocation(database);
        logger.debugf("Using changelog file: %s", changelog);

        ResourceAccessor resourceAccessor = new ClassLoaderResourceAccessor(getClass().getClassLoader());
        DatabaseChangeLog databaseChangeLog = generateDynamicChangeLog(changelog, resourceAccessor, database);
        
        return new Liquibase(databaseChangeLog, resourceAccessor, database);
    }

    /**
     * We want to be able to provide extra changesets as an extension to the Keycloak data model.
     * But we do not want users to be able to not execute certain parts of the Keycloak internal data model.
     * Therefore, we generate a dynamic changelog here that always contains the keycloak changelog file
     * and optionally include the user extension changelog files.
     * 
     * @param changelog the changelog file location
     * @param resourceAccessor the resource accessor
     * @param database the database
     * @return
     */
    private DatabaseChangeLog generateDynamicChangeLog(String changelog, ResourceAccessor resourceAccessor, Database database) throws LiquibaseException {
    	ChangeLogParameters changeLogParameters = new ChangeLogParameters(database);
        ChangeLogParser parser = ChangeLogParserFactory.getInstance().getParser(changelog, resourceAccessor);
        DatabaseChangeLog keycloakDatabaseChangeLog = parser.parse(changelog, changeLogParameters, resourceAccessor);

        List<String> locations = new ArrayList<>();
        Set<JpaEntityProvider> entityProviders = keycloakSession.getAllProviders(JpaEntityProvider.class);
        for (JpaEntityProvider entityProvider : entityProviders) {
            String location = entityProvider.getChangelogLocation();
            if (location != null) {
            	locations.add(location);
            }
        }
        
        final DatabaseChangeLog dynamicMasterChangeLog;
        if (locations.isEmpty()) {
        	// If there are no extra changelog locations, we'll just use the keycloak one.
        	dynamicMasterChangeLog = keycloakDatabaseChangeLog;
        } else {
        	// A change log is essentially not much more than a (big) collection of changesets.
        	// The original (file) destination is not important. So we can just make one big dynamic change log that include all changesets.
            dynamicMasterChangeLog = new DatabaseChangeLog();
            dynamicMasterChangeLog.setChangeLogParameters(changeLogParameters);
            for (ChangeSet changeSet : keycloakDatabaseChangeLog.getChangeSets()) {
            	dynamicMasterChangeLog.addChangeSet(changeSet);
            }
            ProxyClassLoader proxyClassLoader = new ProxyClassLoader(JpaUtils.getProvidedEntities(keycloakSession));
            for (String location : locations) {
            	ResourceAccessor proxyResourceAccessor = new ClassLoaderResourceAccessor(proxyClassLoader);
                ChangeLogParser locationParser = ChangeLogParserFactory.getInstance().getParser(location, proxyResourceAccessor);
                DatabaseChangeLog locationDatabaseChangeLog = locationParser.parse(location, changeLogParameters, proxyResourceAccessor);
                for (ChangeSet changeSet : locationDatabaseChangeLog.getChangeSets()) {
                	dynamicMasterChangeLog.addChangeSet(changeSet);
                }
            }
        }
        
        return dynamicMasterChangeLog;
    }
    
    /**
     * Get the changelog file location that should be used as input for Liquibase.
     * This logic is split into a separate protected method, to allow for easy extends + override when customizing the schema.
     * 
     * @param database the database
     * @return the liquibase changelog location
     */
    protected String getChangelogLocation(Database database) {
        return (database instanceof DB2Database) ? LiquibaseJpaUpdaterProvider.DB2_CHANGELOG : LiquibaseJpaUpdaterProvider.CHANGELOG;
    }

    private static class LogWrapper extends LogFactory {

        private liquibase.logging.Logger logger = new liquibase.logging.Logger() {
            @Override
            public void setName(String name) {
            }

            @Override
            public void setLogLevel(String level) {
            }

            @Override
            public void setLogLevel(LogLevel level) {
            }

            @Override
            public void setLogLevel(String logLevel, String logFile) {
            }

            @Override
            public void severe(String message) {
                DefaultLiquibaseConnectionProvider.logger.error(message);
            }

            @Override
            public void severe(String message, Throwable e) {
                DefaultLiquibaseConnectionProvider.logger.error(message, e);
            }

            @Override
            public void warning(String message) {
                // Ignore this warning as cascaded drops doesn't work anyway with all DBs, which we need to support
                if ("Database does not support drop with cascade".equals(message)) {
                    DefaultLiquibaseConnectionProvider.logger.debug(message);
                } else {
                    DefaultLiquibaseConnectionProvider.logger.warn(message);
                }
            }

            @Override
            public void warning(String message, Throwable e) {
                DefaultLiquibaseConnectionProvider.logger.warn(message, e);
            }

            @Override
            public void info(String message) {
                DefaultLiquibaseConnectionProvider.logger.debug(message);
            }

            @Override
            public void info(String message, Throwable e) {
                DefaultLiquibaseConnectionProvider.logger.debug(message, e);
            }

            @Override
            public void debug(String message) {
                if (DefaultLiquibaseConnectionProvider.logger.isTraceEnabled()) {
                    DefaultLiquibaseConnectionProvider.logger.trace(message);
                }
            }

            @Override
            public LogLevel getLogLevel() {
                if (DefaultLiquibaseConnectionProvider.logger.isTraceEnabled()) {
                    return LogLevel.DEBUG;
                } else if (DefaultLiquibaseConnectionProvider.logger.isDebugEnabled()) {
                    return LogLevel.INFO;
                } else {
                    return LogLevel.WARNING;
                }
            }

            @Override
            public void debug(String message, Throwable e) {
                DefaultLiquibaseConnectionProvider.logger.trace(message, e);
            }

            @Override
            public void setChangeLog(DatabaseChangeLog databaseChangeLog) {
            }

            @Override
            public void setChangeSet(ChangeSet changeSet) {
            }

            @Override
            public int getPriority() {
                return 0;
            }
        };

        @Override
        public liquibase.logging.Logger getLog(String name) {
            return logger;
        }

        @Override
        public liquibase.logging.Logger getLog() {
            return logger;
        }

    }
}
