/*
 * Copyright 2006 Amazon Technologies, Inc. or its affiliates.
 * Amazon, Amazon.com and Carbonado are trademarks or registered trademarks
 * of Amazon Technologies, Inc. or its affiliates.  All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.amazon.carbonado.repo.sleepycat;

import java.io.File;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.sleepycat.je.CheckpointConfig;
import com.sleepycat.je.DatabaseException;
import com.sleepycat.je.Environment;
import com.sleepycat.je.EnvironmentConfig;
import com.sleepycat.je.JEVersion;
import com.sleepycat.je.Transaction;
import com.sleepycat.je.TransactionConfig;

import com.sleepycat.je.util.DbBackup;

import com.amazon.carbonado.ConfigurationException;
import com.amazon.carbonado.IsolationLevel;
import com.amazon.carbonado.Repository;
import com.amazon.carbonado.RepositoryException;
import com.amazon.carbonado.Storable;
import com.amazon.carbonado.SupportException;

/**
 * Repository implementation backed by a Berkeley DB, Java Edition. Data is
 * encoded in the DB in a specialized format, and so this repository should not
 * be used to open arbitrary Berkeley databases. JERepository has total schema
 * ownership, and so it updates type definitions in the storage layer
 * automatically.
 *
 * @author Brian S O'Neill
 */
class JE_Repository extends BDBRepository<JE_Transaction> {
    private static final TransactionConfig
        TXN_READ_UNCOMMITTED,        TXN_READ_COMMITTED,        TXN_REPEATABLE_READ,
        TXN_SERIALIZABLE,
        TXN_READ_UNCOMMITTED_NOWAIT, TXN_READ_COMMITTED_NOWAIT, TXN_REPEATABLE_READ_NOWAIT,
        TXN_SERIALIZABLE_NOWAIT;

    static {
        TXN_READ_UNCOMMITTED = new TransactionConfig();
        TXN_READ_UNCOMMITTED.setReadUncommitted(true);

        TXN_READ_COMMITTED = new TransactionConfig();
        TXN_READ_COMMITTED.setReadCommitted(true);

        TXN_REPEATABLE_READ = TransactionConfig.DEFAULT;

        TXN_SERIALIZABLE = new TransactionConfig();
        TXN_SERIALIZABLE.setSerializableIsolation(true);

        TXN_READ_UNCOMMITTED_NOWAIT = new TransactionConfig();
        TXN_READ_UNCOMMITTED_NOWAIT.setReadUncommitted(true);
        TXN_READ_UNCOMMITTED_NOWAIT.setNoWait(true);

        TXN_READ_COMMITTED_NOWAIT = new TransactionConfig();
        TXN_READ_COMMITTED_NOWAIT.setReadCommitted(true);
        TXN_READ_COMMITTED_NOWAIT.setNoWait(true);

        TXN_REPEATABLE_READ_NOWAIT = new TransactionConfig();
        TXN_REPEATABLE_READ_NOWAIT.setNoWait(true);

        TXN_SERIALIZABLE_NOWAIT = new TransactionConfig();
        TXN_SERIALIZABLE_NOWAIT.setSerializableIsolation(true);
        TXN_SERIALIZABLE_NOWAIT.setNoWait(true);
    }

    final Environment mEnv;
    final boolean mDatabasesTransactional;

    private DbBackup mBackup;

    /**
     * Open the repository using the given BDB repository configuration.
     *
     * @throws IllegalArgumentException if name or environment home is null
     * @throws RepositoryException if there is a problem opening the environment
     */
    JE_Repository(AtomicReference<Repository> rootRef, BDBRepositoryBuilder builder)
        throws RepositoryException
    {
        super(rootRef, builder, JE_ExceptionTransformer.getInstance());

        if (JEVersion.CURRENT_VERSION.getMajor() < 3) {
            // Although repository can work just fine with older versions, some
            // have bugs which cause severe storage corruption.
            throw new SupportException
                ("BDB-JE version is too old. Major version must at least be 3: " +
                 JEVersion.CURRENT_VERSION);
        }

        EnvironmentConfig envConfig;
        try {
            envConfig = (EnvironmentConfig) builder.getInitialEnvironmentConfig();
        } catch (ClassCastException e) {
            throw new ConfigurationException
                ("Unsupported initial environment config. Must be instance of " +
                 EnvironmentConfig.class.getName(), e);
        }

        if (envConfig == null) {
            envConfig = new EnvironmentConfig();
            envConfig.setTransactional(true);
            envConfig.setReadOnly(builder.getReadOnly());
            envConfig.setAllowCreate(!builder.getReadOnly());
            envConfig.setTxnNoSync(builder.getTransactionNoSync());
            envConfig.setTxnWriteNoSync(builder.getTransactionWriteNoSync());
            if (builder.getLogInMemory()) {
                envConfig.setConfigParam("je.log.memOnly", "true");
            }

            try {
                Integer maxSize = builder.getLogFileMaxSize();
                if (maxSize != null) {
                    envConfig.setConfigParam("je.log.fileMax", maxSize.toString());
                }
            } catch (NoSuchMethodError e) {
                // Carbonado package might be older.
            }

            Boolean checksumEnabled = builder.getChecksumEnabled();
            if (checksumEnabled != null) {
                envConfig.setConfigParam("je.log.checksumRead", checksumEnabled.toString());
            }

            Integer cachePercent = builder.getCachePercent();
            if (cachePercent != null && cachePercent > 0) {
                envConfig.setConfigParam("je.maxMemoryPercent", cachePercent.toString());
            }

            // cacheSize will override any existing maxMemoryPercent setting
            Long cacheSize = builder.getCacheSize();
            if (cacheSize != null && cacheSize > 0) {
                envConfig.setConfigParam("je.maxMemory", cacheSize.toString());
            }

            envConfig.setConfigParam("je.lock.timeout",
                        String.valueOf(builder.getLockTimeoutInMicroseconds()));
            envConfig.setConfigParam("je.txn.timeout",
                        String.valueOf(builder.getTransactionTimeoutInMicroseconds()));

        } else {
            if (!envConfig.getTransactional()) {
                throw new IllegalArgumentException("EnvironmentConfig: getTransactional is false");
            }
        }

        try {
            mEnv = new Environment(builder.getEnvironmentHomeFile(), envConfig);
        } catch (DatabaseException e) {
            throw JE_ExceptionTransformer.getInstance().toRepositoryException(e);
        } catch (Throwable e) {
            String message = "Unable to open environment";
            if (e.getMessage() != null) {
                message += ": " + e.getMessage();
            }
            throw new RepositoryException(message, e);
        }

        boolean databasesTransactional = envConfig.getTransactional();
        if (builder.getDatabasesTransactional() != null) {
            databasesTransactional = builder.getDatabasesTransactional();
        }

        mDatabasesTransactional = databasesTransactional;

        start(0, 0, builder);
    }

    public Object getEnvironment() {
        return mEnv;
    }

    public BDBProduct getBDBProduct() {
        return BDBProduct.JE;
    }

    public int[] getVersion() {
        JEVersion version = JEVersion.CURRENT_VERSION;
        return new int[] {version.getMajor(), version.getMinor(), version.getPatch()};
    }

    public File getHome() {
        return mEnvHome;
    }

    public File getDataHome() {
        return mEnvHome;
    }

    @Override
    IsolationLevel selectIsolationLevel(com.amazon.carbonado.Transaction parent,
                                        IsolationLevel level)
    {
        if (level == null) {
            if (parent == null) {
                return IsolationLevel.REPEATABLE_READ;
            }
            return parent.getIsolationLevel();
        }

        if (level == IsolationLevel.SNAPSHOT) {
            // Not supported.
            return null;
        }

        if (parent != null && parent.getIsolationLevel() != IsolationLevel.NONE) {
            // Nested transactions are not supported, so they are faked.
            if (level != IsolationLevel.NONE) {
                // Allow requested isolation level to be lower, but it actually
                // runs at parent level. Returning null indicates new level is
                // not allowed and TransactionManager converts this to an
                // UnsupportedOperationException.
                IsolationLevel parentLevel = parent.getIsolationLevel();
                level = parentLevel.compareTo(level) >= 0 ? parentLevel : null;
            }
        }

        return level;
    }

    @Override
    protected JE_Transaction txn_begin(JE_Transaction parent, IsolationLevel level)
        throws Exception
    {
        // Nested transactions aren't supported in BDB-JE, so fake it. This
        // means that isolation level cannot be increased.
        if (parent != null) {
            return parent.createChild();
        }

        TransactionConfig config;
        switch (level) {
        case READ_UNCOMMITTED:
            config = TXN_READ_UNCOMMITTED;
            break;
        case READ_COMMITTED:
            config = TXN_READ_COMMITTED;
            break;
        default:
            config = TXN_REPEATABLE_READ;
            break;
        case SERIALIZABLE:
            config = TXN_SERIALIZABLE;
            break;
        }

        return new JE_Transaction(mEnv.beginTransaction(null, config));
    }

    @Override
    protected JE_Transaction txn_begin(JE_Transaction parent, IsolationLevel level,
                                       int timeout, TimeUnit unit)
        throws Exception
    {
        if (parent != null) {
            return parent.createChild(mEnv, unit.toMicros(timeout));
        }

        JE_Transaction txn = txn_begin(null, level);
        txn.setLockTimeout(unit.toMicros(timeout));
        return txn;
    }

    @Override
    protected JE_Transaction txn_begin_nowait(JE_Transaction parent, IsolationLevel level)
        throws Exception
    {
        if (parent != null) {
            return parent.createChild(mEnv, 0);
        }

        TransactionConfig config;
        switch (level) {
        case READ_UNCOMMITTED:
            config = TXN_READ_UNCOMMITTED_NOWAIT;
            break;
        case READ_COMMITTED:
            config = TXN_READ_COMMITTED_NOWAIT;
            break;
        default:
            config = TXN_REPEATABLE_READ_NOWAIT;
            break;
        case SERIALIZABLE:
            config = TXN_SERIALIZABLE_NOWAIT;
            break;
        }

        return new JE_Transaction(mEnv.beginTransaction(null, config));
    }

    @Override
    protected void txn_commit(JE_Transaction txn) throws Exception {
        txn.commit();
    }

    @Override
    protected void txn_abort(JE_Transaction txn) throws Exception {
        txn.abort();
    }

    @Override
    protected void env_checkpoint() throws Exception {
        CheckpointConfig cc = new CheckpointConfig();
        cc.setForce(true);
        mEnv.checkpoint(cc);
    }

    @Override
    protected void env_checkpoint(int kBytes, int minutes) throws Exception {
        CheckpointConfig cc = new CheckpointConfig();
        cc.setKBytes(kBytes);
        cc.setMinutes(minutes);
        mEnv.checkpoint(cc);
    }

    @Override
    protected void env_detectDeadlocks() throws Exception {
        // Unsupported feature
    }

    @Override
    protected void env_close() throws Exception {
        if (mEnv != null) {
            mEnv.close();
        }
    }

    @Override
    protected <S extends Storable> BDBStorage<JE_Transaction, S> createBDBStorage(Class<S> type)
        throws Exception
    {
        return new JE_Storage<S>(this, type);
    }

    @Override
    void enterBackupMode() throws Exception {
        DbBackup backup = new DbBackup(mEnv);
        backup.startBackup();
        mBackup = backup;
    }

    @Override
    void exitBackupMode() throws Exception {
        DbBackup backup = mBackup;
        if (backup != null) {
            try {
                backup.endBackup();
            } finally {
                mBackup = null;
            }
        }
    }

    @Override
    File[] backupFiles() throws Exception {
        File home = mEnv.getHome();

        String[] names = mBackup.getLogFilesInBackupSet();
        File[] files = new File[names.length];
        for (int i=0; i<names.length; i++) {
            files[i] = new File(home, names[i]);
        }

        return files;
    }
}
