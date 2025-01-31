/*
 * Copyright 2022 The Android Open Source Project
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

package androidx.build.dackka

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MetadataEntryTest {

    @Test
    fun `toMap() with groupId containing a single period`() {
        val entry = MetadataEntry(
            groupId = "androidx.groupId",
            artifactId = "artifactId",
            sourceDir = "androidx/"
        )
        val map = entry.toMap()

        /* ktlint-disable max-line-length */
        assertThat(map["groupId"]).isEqualTo("androidx.groupId")
        assertThat(map["artifactId"]).isEqualTo("artifactId")
        assertThat(map["releaseNotesUrl"]).isEqualTo("https://developer.android.com/jetpack/androidx/releases/groupId")
        assertThat(map["sourceDir"]).isEqualTo("androidx/")
        /* ktlint-enable max-line-length */
    }

    @Test
    fun `toMap() with groupId containing multiple periods`() {
        val entry = MetadataEntry(
            groupId = "androidx.arch.core",
            artifactId = "artifactId",
            sourceDir = "androidx/"
        )
        val map = entry.toMap()

        /* ktlint-disable max-line-length */
        assertThat(map["groupId"]).isEqualTo("androidx.arch.core")
        assertThat(map["artifactId"]).isEqualTo("artifactId")
        assertThat(map["releaseNotesUrl"]).isEqualTo("https://developer.android.com/jetpack/androidx/releases/arch-core")
        assertThat(map["sourceDir"]).isEqualTo("androidx/")
        /* ktlint-enable max-line-length */
    }
}