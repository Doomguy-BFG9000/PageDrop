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

package app.pagedrop.data.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import app.pagedrop.data.BookRepository
import app.pagedrop.data.DefaultBookRepository
import app.pagedrop.data.local.database.Book
import javax.inject.Inject
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
interface DataModule {

    @Singleton
    @Binds
    fun bindsBookRepository(
        bookRepository: DefaultBookRepository
    ): BookRepository
}

class FakeBookRepository @Inject constructor() : BookRepository {
    override fun getBooks(): Flow<List<Book>> = flowOf(fakeBooks)

    override suspend fun addBook(context: android.content.Context, uri: android.net.Uri): Book {
        throw NotImplementedError()
    }

    override suspend fun convertAndAddBook(
        context: android.content.Context,
        uri: android.net.Uri,
        outputTitle: String?
    ): Book {
        throw NotImplementedError()
    }

    override suspend fun deleteBook(book: Book) {
        throw NotImplementedError()
    }

    override suspend fun getBookById(id: Int): Book? {
        throw NotImplementedError()
    }

    override suspend fun markTransferred(uid: Int) {
        throw NotImplementedError()
    }

    override suspend fun markTransferredBookIds(uids: List<Int>) {
        throw NotImplementedError()
    }

    override suspend fun updateBookMetadata(book: Book, title: String, author: String, coverPath: String?): Book {
        throw NotImplementedError()
    }

    override suspend fun addBookFromFile(book: Book): Long {
        throw NotImplementedError()
    }
}

val fakeBooks = listOf(
    Book(
        uid = 1,
        title = "The Great Gatsby",
        author = "F. Scott Fitzgerald",
        fileName = "gatsby.azw3",
        filePath = "/fake/gatsby.azw3",
        format = "AZW3",
        fileSize = 2_500_000
    ),
    Book(
        uid = 2,
        title = "1984",
        author = "George Orwell",
        fileName = "1984.mobi",
        filePath = "/fake/1984.mobi",
        format = "MOBI",
        fileSize = 1_800_000
    ),
    Book(
        uid = 3,
        title = "Pride and Prejudice",
        author = "Jane Austen",
        fileName = "pride.pdf",
        filePath = "/fake/pride.pdf",
        format = "PDF",
        fileSize = 3_100_000
    )
)
