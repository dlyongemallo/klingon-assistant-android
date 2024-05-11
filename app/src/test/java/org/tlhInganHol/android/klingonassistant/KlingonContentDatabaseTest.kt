/*
 * Copyright (C) 2021 De'vID jonpIn (David Yonge-Mallo)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.tlhInganHol.android.klingonassistant

import org.junit.Test
/**
 * Unit tests for KlingonContentDatabase logic.
 */
class KlingonContentDatabaseTest {
    @Test
    @Throws(java.lang.Exception::class)
    fun klingonContentDatabase_overrideXifanHol() {
        val database = KlingonContentDatabase(null)
        val result = database.getEntryMatches("+xifan hol")
        assertEquals(result!!.count, 0)
    }
}
