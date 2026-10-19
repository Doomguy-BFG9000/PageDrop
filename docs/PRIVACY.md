# What leaves your phone

PageDrop has no account to create, no analytics, no crash reporting and no ads. The app contains no tracking or advertising libraries; the dependency list is in [`app/build.gradle.kts`](../app/build.gradle.kts).

It does connect to other machines to do its job. This page lists every host PageDrop itself contacts, and separately the sites you open yourself.

## Your Kindle (local Wi-Fi only)

PageDrop scans your phone's Wi-Fi network for your Kindle and connects to KOReader's SSH server. Books, covers, fonts, dictionaries and screensavers go to the Kindle over SFTP. Reading the Kindle's library, deleting books and importing KOReader highlights and reading progress use the same connection.

This traffic stays on your local network. The Kindle's address and SSH password are stored on your phone.

## Hosts PageDrop contacts

Only the hosts in the first group are contacted by default as you use the app. The others are contacted only when you use the feature named next to them.

### PageDrop's conversion server (on by default)

- **The PageDrop conversion server (Supabase)**, at `bzvvpwykluklvvjyzytn.supabase.co`, run by PageDrop's developer. Builds made with your own server URL talk to your server instead; builds made with no server URL never contact one.

<!-- Retention and quota wording below is true once fix/server-launch-hardening is deployed. -->

While the server switch is on:

- **Book files that need converting** are uploaded with their cover and the title and author you chose, converted with Calibre, and downloaded back to your phone. That covers EPUB, text and other non-Kindle formats, and PDFs when you choose to reflow them. Kindle-ready files (MOBI, AZW3, AZW, PRC) and PDFs you keep as PDF are not uploaded.
- **Articles you save** are sent as a link. The server fetches the page, formats it and sends back a book.
- **Deletion:** the server's cleanup job deletes each job's uploaded file, cover, converted result and job record about 24 hours after the job was created (the cleanup runs every 10 minutes by default).
- **Quotas:** each user can start 20 jobs per day (UTC), with at most 100 MB per file, 15 MB per cover and 500 MB of uploads held at a time.
- **No account:** the app signs in anonymously. You get a random ID: no email, no name, no password.
- **Before your first upload** PageDrop asks whether to use the server or keep everything on the phone. If you decline, conversion runs on the phone.

The server is open source and lives in [`server/`](../server/). You can run your own; see [server/README.md](../server/README.md).

**The switch:** Settings → Conversion server → Use PageDrop conversion server. With it off, nothing is sent to the server: EPUB and text conversion, PDF reflow and article extraction all run on the phone. Choosing an output format other than a Kindle book is only offered while the server is on. If the server is on but fails or can't be reached, PageDrop falls back to converting on the phone.

### Book sources (when you search or download)

Your search words go to each built-in source you have switched on in Settings → Book sources (all are on by default), and to any OPDS catalog you add. Downloads come straight from the source.

- Project Gutenberg: `gutendex.com` for search, `gutenberg.org` for details and downloads
- Standard Ebooks: `standardebooks.org`
- Internet Archive: `archive.org`, including `archive.org/services/img` for covers and the Digital Library of India collection
- Open Library: `openlibrary.org` (search) and `covers.openlibrary.org` (covers)
- Wikisource: `en.wikisource.org` and `hi.wikisource.org`
- ManyBooks: `manybooks.net`
- Faded Page: `fadedpage.com`
- Hindwi: `hindwi.org`
- Rekhta: `rekhta.org`
- Your own OPDS catalogs: whatever servers you enter. None is pre-filled.
- Cover images are loaded from whatever URL a source or OPDS catalog lists for them, which may be another host.

### Book details (titles, authors, covers)

To fill in title, author and cover, PageDrop sends the book's title and author, or its ISBN when the file has one, to Open Library (`openlibrary.org`, `covers.openlibrary.org`), and downloads covers from the results. No file content is sent.

**Google Books** (`googleapis.com`, with covers from `books.google.com`) gets the same title, author or ISBN only while Settings → Book details → Use Google Books for book details is on. It is on by default in builds that include the conversion server (GitHub and IzzyOnDroid releases) and off by default in builds without one (F-Droid). With it off, PageDrop never contacts Google Books and Open Library still fills in details.

### Saved articles on the phone

When the conversion server is off, or it fails, PageDrop fetches the article's page directly from the site you gave it.

### Extras downloads

Only when you tap to install one: fonts from GitHub (`github.com`) and dictionaries from SourceForge (`downloads.sourceforge.net`).

### The Kindle Modding Wiki

The "Get your Kindle ready" walkthrough opens pages on [kindlemodding.org](https://kindlemodding.org/) in your browser. PageDrop sends it nothing beyond loading those pages.

## Sites you open yourself

Search results can show website shortcuts. They do nothing until you tap one. Then the site opens in PageDrop's built-in browser, which is a WebView with JavaScript switched on, and the site sees you as any browser would.

- Global Grey (through a Google search, so `google.com` sees the query), ePustakalay, and, when a built-in source above can't be searched, its website (for example ManyBooks, Faded Page, Hindwi or Rekhta)
- Anna's Archive and OceanofPDF, only if you switch on "Show shadow-library search shortcuts" in Settings → Book sources (off by default)

Cookies from the sites you visit are stored in the app's browser. When you download a book from a site you are browsing, PageDrop sends that site's cookies with the download request to the same site. Clearing PageDrop's app data (Android Settings → Apps → PageDrop → Storage) removes them.

---

Found something that leaves your phone and isn't listed here? That's a bug. [Open an issue](https://github.com/spandan-kumar/PageDrop/issues/new/choose).
