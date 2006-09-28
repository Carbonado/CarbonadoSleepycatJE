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

import com.sleepycat.je.Cursor;
import com.sleepycat.je.Database;
import com.sleepycat.je.DatabaseConfig;
import com.sleepycat.je.DatabaseEntry;
import com.sleepycat.je.DatabaseException;
import com.sleepycat.je.Environment;
import com.sleepycat.je.LockMode;
import com.sleepycat.je.OperationStatus;
import com.sleepycat.je.Transaction;

import com.amazon.carbonado.ConfigurationException;
import com.amazon.carbonado.FetchException;
import com.amazon.carbonado.RepositoryException;
import com.amazon.carbonado.Storable;

/**
 * Storage implementation for JERepository.
 *
 * @author Brian S O'Neill
 * @author Nicole Deflaux
 */
// FIXME: Rename to JE_Storage. API appears stable.
class JE2_0_Storage<S extends Storable> extends BDBStorage<Transaction, S> {
    // Primary database of Storable instances
    private Database mDatabase;

    /**
     *
     * @param repository repository reference
     * @param storableFactory factory for emitting storables
     * @param db database for Storables
     * @throws DatabaseException
     * @throws SupportException
     */
    JE2_0_Storage(JE2_0_Repository repository, Class<S> type)
        throws DatabaseException, RepositoryException
    {
        super(repository, type);
        open(repository.mEnv.getConfig().getReadOnly());
    }

    @Override
    public long countAll() throws FetchException {
        try {
            return mDatabase.count();
        } catch (DatabaseException e) {
            throw JE2_0_ExceptionTransformer.getInstance().transformIntoFetchException(e);
        }
    }

    protected boolean db_exists(Transaction txn, byte[] key, boolean rmw) throws Exception {
        DatabaseEntry keyEntry = new DatabaseEntry(key);
        DatabaseEntry dataEntry = new DatabaseEntry();
        dataEntry.setPartial(0, 0, true);
        OperationStatus status = mDatabase.get
            (txn, keyEntry, dataEntry, rmw ? LockMode.RMW : null);
        return status != OperationStatus.NOTFOUND;
    }

    protected byte[] db_get(Transaction txn, byte[] key, boolean rmw) throws Exception {
        DatabaseEntry keyEntry = new DatabaseEntry(key);
        DatabaseEntry dataEntry = new DatabaseEntry();
        OperationStatus status = mDatabase.get
            (txn, keyEntry, dataEntry, rmw ? LockMode.RMW : null);
        if (status == OperationStatus.NOTFOUND) {
            return NOT_FOUND;
        }
        return dataEntry.getData();
    }

    protected Object db_putNoOverwrite(Transaction txn, byte[] key, byte[] value)
        throws Exception
    {
        DatabaseEntry keyEntry = new DatabaseEntry(key);
        DatabaseEntry dataEntry = new DatabaseEntry(value);
        OperationStatus status = mDatabase.putNoOverwrite(txn, keyEntry, dataEntry);
        if (status == OperationStatus.SUCCESS) {
            return SUCCESS;
        } else if (status == OperationStatus.KEYEXIST) {
            return KEY_EXIST;
        } else {
            return NOT_FOUND;
        }
    }

    protected boolean db_put(Transaction txn, byte[] key, byte[] value)
        throws Exception
    {
        DatabaseEntry keyEntry = new DatabaseEntry(key);
        DatabaseEntry dataEntry = new DatabaseEntry(value);
        return mDatabase.put(txn, keyEntry, dataEntry) == OperationStatus.SUCCESS;
    }

    protected boolean db_delete(Transaction txn, byte[] key) throws Exception {
        DatabaseEntry keyEntry = new DatabaseEntry(key);
        return mDatabase.delete(txn, keyEntry) == OperationStatus.SUCCESS;
    }

    protected void db_truncate(Transaction txn) throws Exception {
        // TODO: Do this the non-deprecated way, which involves closing all
        // database handles first.
        //mDatabase.truncate(txn, false);
        throw new UnsupportedOperationException();
    }

    protected boolean db_isEmpty(Transaction txn, Object database, boolean rmw) throws Exception {
        Cursor cursor = ((Database) database).openCursor(txn, null);
        OperationStatus status = cursor.getFirst
            (new DatabaseEntry(), new DatabaseEntry(), rmw ? LockMode.RMW : null);
        cursor.close();
        return status == OperationStatus.NOTFOUND;
    }

    protected void db_close(Object database) throws Exception {
        ((Database) database).close();
    }

    protected Object env_openPrimaryDatabase(Transaction txn, String name)
        throws Exception
    {
        JE2_0_Repository repository = (JE2_0_Repository) getRepository();
        Environment env = repository.mEnv;
        boolean readOnly = env.getConfig().getReadOnly();

        DatabaseConfig config;
        try {
            config = (DatabaseConfig)
                repository.getInitialDatabaseConfig();
        } catch (ClassCastException e) {
            throw new ConfigurationException
                ("Unsupported initial environment config. Must be instance of "
                 + DatabaseConfig.class.getName(), e);
        }

        if(config == null) {
            config = new DatabaseConfig();
            config.setSortedDuplicates(false);
        }
        else {
            if(config.getSortedDuplicates()) {
                throw new IllegalArgumentException("DatabaseConfig: getSortedDuplicates is true");
            }
        }

        // Overwrite these settings as they depend upon the
        // configuration of the repository
        config.setTransactional(repository.mDatabasesTransactional);
        config.setReadOnly(readOnly);
        config.setAllowCreate(!readOnly);

        runDatabasePrepareForOpeningHook(config);

        return mDatabase = env.openDatabase(txn, name, config);
    }

    protected void env_removeDatabase(Transaction txn, String databaseName) throws Exception {
        mDatabase.getEnvironment().removeDatabase(txn, databaseName);
    }

    protected BDBCursor<Transaction, S> openCursor
        (BDBTransactionManager<Transaction> txnMgr,
         byte[] startBound, boolean inclusiveStart,
         byte[] endBound, boolean inclusiveEnd,
         int maxPrefix,
         boolean reverse,
         Object database)
        throws Exception
    {
        return new JE2_0_Cursor<S>
            (txnMgr,
             startBound, inclusiveStart,
             endBound, inclusiveEnd,
             maxPrefix,
             reverse,
             this,
             (Database) database);
    }
}
