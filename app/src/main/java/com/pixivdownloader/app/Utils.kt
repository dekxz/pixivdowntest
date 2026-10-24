package com.pixivdownloader.app

object Utils {
    /** Artwork page, with or without a language segment (/en/, /zh-tw/ ...). */
    private val ARTWORK_URL = Regex(
        """https?://(?:www\.)?pixiv\.net/(?:[a-z]{2}(?:-[a-z]+)?/)?artworks/\d+""",
        RegexOption.IGNORE_CASE
    )

    /** Direct image URL, e.g. https://i.pximg.net/img-original/img/2024/01/01/00/00/00/12345678_p0.png */
    private val PXIMG_URL = Regex("""https?://i\.pximg\.net/\S*?/\d+_p\d+[._]\S*""")

    private val ARTWORK_ID = Regex("""artworks/(\d+)""")
    private val PXIMG_ID = Regex("""/(\d+)_p\d+[._]""")

    private val BAD_CHARS = Regex("""[<>:"/\\|?*\p{Cntrl}]""")

    /** Finds a Pixiv URL inside arbitrary text (clipboard / share sheet). */
    fun findPixivUrl(text: String?): String? =
        text?.let { ARTWORK_URL.find(it)?.value ?: PXIMG_URL.find(it)?.value }

    /** Same job as PixivDownloader.extract_artwork_id in the desktop script. */
    fun extractArtworkId(url: String): String? {
        if ("i.pximg.net" in url) PXIMG_ID.find(url)?.let { return it.groupValues[1] }
        return ARTWORK_ID.find(url)?.groupValues?.get(1)
    }

    /** Removes characters that are illegal in file names and keeps the result a sane length. */
    fun sanitizeName(name: String?, fallback: String, maxLength: Int = 100): String {
        var s = BAD_CHARS.replace(name.orEmpty(), "").trim().trim('.').trim()
        if (s.length > maxLength) {
            s = s.substring(0, maxLength)
            if (s.isNotEmpty() && s.last().isHighSurrogate()) s = s.dropLast(1)
            s = s.trim()
        }
        return s.ifEmpty { fallback }
    }
}
