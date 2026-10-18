# What leaves your phone

PageDrop has no account to create, no analytics, no crash reporting and no ads. The app contains no tracking or advertising libraries; the dependency list is in [`app/build.gradle.kts`](../app/build.gradle.kts).

It still talks to other machines. This is every one of them.

## Your Kindle (local Wi-Fi only)

PageDrop finds your Kindle on your local network and connects to KOReader's SSH server. Books, covers, fonts, dictionaries and screensavers go to the Kindle over SFTP. Reading the Kindle's library, deleting books and importing KOReader highlights and reading progress happen over the same connection.

This traffic stays on your Wi-Fi. The Kindle's address and SSH password are stored on your phone.

## The book sources you search

When you search, your query goes to each book source you have switched on in Settings: Project Gutenberg (and Gutendex, which indexes it), Standard Ebooks, the Internet Archive, Open Library, Wikisource and the others listed there, plus any OPDS catalogs you add. When you download a book, it comes straight from that source.

Search shortcuts to other websites do nothing until you tap one. Shortcuts to shadow libraries are hidden unless you switch them on in Settings. Then that site opens in PageDrop's built-in browser and sees you as any browser would.

## Open Library and Google Books (book details)

To fill in title, author and cover, PageDrop sends the book's title and author, or its ISBN when the file has one, to Open Library's search API and the Google Books API, and downloads covers from them. No file content is sent.

## PageDrop's conversion server (on by default)

Release builds come with PageDrop's conversion server switched on. The server is run by PageDrop's developer. Before the first upload, PageDrop asks whether to use it or keep everything on the phone. While it is on:

- **Books that need converting** are uploaded to the server with their cover and the title and author you chose, converted with Calibre, and downloaded back to your phone. That means EPUB, text and other non-Kindle formats, and PDFs when you choose to reflow them. Kindle-ready files (MOBI, AZW3, AZW, PRC) and PDFs you keep as PDF are never uploaded.
- **Articles you save** are sent as a link. The server fetches the page, formats it and sends back a book.
- **Uploaded files and converted results are deleted after 24 hours.**
- The app signs in to the server anonymously. You get a random ID, not an account: no email, no name, no password.

**One switch keeps everything on the phone:** Settings → Conversion server → Use PageDrop conversion server. With it off, books are converted on the phone and nothing is sent to the server.

The server is open source and lives in [`server/`](../server/). You can run your own; see [server/README.md](../server/README.md). Builds made without a server URL and key have no conversion server at all.

## Downloads you ask for

The Extras section downloads fonts from GitHub and dictionaries from SourceForge, only when you tap to install one.

## The Kindle Modding Wiki

The "Get your Kindle ready" walkthrough opens pages on [kindlemodding.org](https://kindlemodding.org/). PageDrop sends it nothing beyond loading those pages.

---

Found something that leaves your phone and isn't listed here? That's a bug. [Open an issue](https://github.com/spandan-kumar/PageDrop/issues/new/choose).
