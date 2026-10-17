/*
 * Copyright (C) 2022 The Android Open Source Project
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

package app.pagedrop.data.local.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity
data class Book(
    @PrimaryKey(autoGenerate = true) val uid: Int = 0,
    val title: String,
    val author: String,
    val fileName: String,
    val filePath: String,
    val format: String, // AZW3, MOBI, PDF, TXT
    val fileSize: Long, // bytes
    val coverPath: String? = null,
    val kindleUuid: String? = null,
    val addedDate: Long = System.currentTimeMillis(),
    val lastTransferred: Long? = null
)

@Dao
interface BookDao {
    @Query("SELECT * FROM book ORDER BY addedDate DESC")
    fun getBooks(): Flow<List<Book>>

    @Query("SELECT * FROM book WHERE uid = :uid")
    suspend fun getBookById(uid: Int): Book?

    @Insert
    suspend fun insertBook(item: Book): Long

    @Delete
    suspend fun deleteBook(item: Book)

    @Query("UPDATE book SET lastTransferred = :timestamp WHERE uid = :uid")
    suspend fun updateLastTransferred(uid: Int, timestamp: Long)

    @Query("UPDATE book SET lastTransferred = :timestamp WHERE uid IN (:ids)")
    suspend fun markTransferred(ids: List<Int>, timestamp: Long)

    @Query("UPDATE book SET lastTransferred = NULL WHERE uid IN (:ids)")
    suspend fun clearTransferred(ids: List<Int>)

    @Query("UPDATE book SET coverPath = :coverPath, kindleUuid = :kindleUuid WHERE uid = :uid")
    suspend fun updateCoverAndUuid(uid: Int, coverPath: String?, kindleUuid: String)

    @Query("UPDATE book SET title = :title, author = :author, coverPath = :coverPath WHERE uid = :uid")
    suspend fun updateMetadata(uid: Int, title: String, author: String, coverPath: String?)

    @Query("UPDATE book SET fileName=:fileName, filePath=:filePath, fileSize=:fileSize, title=:title, author=:author, coverPath=:coverPath WHERE uid=:uid")
    suspend fun updateFileAndMetadata(
        uid: Int,
        fileName: String,
        filePath: String,
        fileSize: Long,
        title: String,
        author: String,
        coverPath: String?,
    )
}
