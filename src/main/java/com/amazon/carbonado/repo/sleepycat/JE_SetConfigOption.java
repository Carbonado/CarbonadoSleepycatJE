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

import java.lang.reflect.Method;

import com.amazon.carbonado.RepositoryException;

/**
 * BDB-JE changed all the config setting methods in version 4.0, breaking
 * compatibility. Set the options via reflection to support new and old
 * versions of BDB-JE.
 *
 * @author Brian S O'Neill
 */
class JE_SetConfigOption {
    static void setBooleanParam(Object config, String methodName, boolean value)
        throws RepositoryException
    {
        try {
            Method method = config.getClass().getMethod(methodName, boolean.class);
            method.invoke(config, value);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RepositoryException(e);
        }
    }

    static void setIntParam(Object config, String methodName, int value)
        throws RepositoryException
    {
        try {
            Method method = config.getClass().getMethod(methodName, int.class);
            method.invoke(config, value);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RepositoryException(e);
        }
    }

    static void setNamedParam(Object config, String paramName, String value)
        throws RepositoryException
    {
        try {
            Method method = config.getClass().getMethod
                ("setConfigParam", String.class, String.class);
            method.invoke(config, paramName, value);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RepositoryException(e);
        }
    }
    
}
