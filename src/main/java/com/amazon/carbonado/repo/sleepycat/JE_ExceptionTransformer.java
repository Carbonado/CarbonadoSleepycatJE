/*
 * Copyright 2006-2010 Amazon Technologies, Inc. or its affiliates.
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

import com.sleepycat.je.DatabaseException;
import com.sleepycat.je.DeadlockException;
import com.sleepycat.je.LockNotGrantedException;

import com.amazon.carbonado.FetchDeadlockException;
import com.amazon.carbonado.FetchException;
import com.amazon.carbonado.FetchTimeoutException;
import com.amazon.carbonado.PersistDeadlockException;
import com.amazon.carbonado.PersistDeniedException;
import com.amazon.carbonado.PersistException;
import com.amazon.carbonado.PersistTimeoutException;
import com.amazon.carbonado.spi.ExceptionTransformer;

/**
 * Custom exception transform rules.
 *
 * @author Brian S O'Neill
 */
class JE_ExceptionTransformer extends ExceptionTransformer {
    private static JE_ExceptionTransformer cInstance;

    public static JE_ExceptionTransformer getInstance() {
        if (cInstance == null) {
            cInstance = new JE_ExceptionTransformer();
        }
        return cInstance;
    }

    JE_ExceptionTransformer() {
    }

    @Override
    protected FetchException transformIntoFetchException(Throwable e) {
        FetchException fe = super.transformIntoFetchException(e);
        if (fe != null) {
            return fe;
        }
        if (e instanceof DatabaseException) {
            if (isTimeout(e)) {
                return new FetchTimeoutException(e);
            }
            if (isDeadlock(e)) {
                return new FetchDeadlockException(e);
            }
        }
        return null;
    }

    @Override
    protected PersistException transformIntoPersistException(Throwable e) {
        PersistException pe = super.transformIntoPersistException(e);
        if (pe != null) {
            return pe;
        }
        if (e instanceof DatabaseException) {
            if (isTimeout(e)) {
                return new PersistTimeoutException(e);
            }
            if (isDeadlock(e)) {
                return new PersistDeadlockException(e);
            }
            String message = e.getMessage();
            if (message != null && message.toUpperCase().indexOf("READ ONLY") >= 0) {
                return new PersistDeniedException(e);
            }
        } else if (e instanceof UnsupportedOperationException) {
            String message = e.getMessage();
            if (message != null && message.toUpperCase().indexOf("READ ONLY") >= 0) {
                return new PersistDeniedException(e);
            }
        }
        return null;
    }

    private static boolean isTimeout(Throwable e) {
        return (e.getClass().equals(LockNotGrantedException.class)) ||
            e.getClass().getName().endsWith(".LockNotAvailableException") ||
            e.getClass().getName().endsWith(".LockTimeoutException") ||
            e.getClass().getName().endsWith(".TransactionTimeoutException");
    }

    private static boolean isDeadlock(Throwable e) {
        return (e.getClass().equals(DeadlockException.class)) ||
            e.getClass().getName().endsWith(".LockConflictException");
    }
}
