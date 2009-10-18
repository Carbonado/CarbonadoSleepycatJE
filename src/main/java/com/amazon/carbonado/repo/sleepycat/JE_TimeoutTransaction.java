/*
 * Copyright 2009 Amazon Technologies, Inc. or its affiliates.
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
import com.sleepycat.je.Environment;

/**
 * Nested transaction with a timeout.
 *
 * @author Brian S O'Neill
 */
class JE_TimeoutTransaction extends JE_Transaction {
    private final long mOriginalTimeout;
    private long mLockTimeout;

    JE_TimeoutTransaction(JE_Transaction parent, Environment env, long timeout)
        throws DatabaseException
    {
        super(parent);
        long originalTimeout = env.getConfig().getLockTimeout();
        mOriginalTimeout = originalTimeout <= 0 ? -1 : originalTimeout;
        setLockTimeout(timeout);
    }

    private JE_TimeoutTransaction(JE_TimeoutTransaction parent) throws DatabaseException {
        super(parent);
        mOriginalTimeout = mLockTimeout = parent.mLockTimeout;
    }

    @Override
    JE_Transaction createChild() throws DatabaseException {
        return new JE_TimeoutTransaction(this);
    }

    @Override
    void abort() throws DatabaseException {
        super.abort();
        mParent.setLockTimeout(mOriginalTimeout);
    }

    @Override
    void setLockTimeout(long timeout) throws DatabaseException {
        mParent.setLockTimeout(timeout);
        mLockTimeout = timeout;
    }
}
