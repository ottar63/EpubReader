package com.example.epubreader

import androidx.compose.foundation.layout.PaddingValues
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Xml
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.epubreader.ui.theme.EpubReaderTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.TextButton



class MainActivity : ComponentActivity() {

    private val preferences by lazy {
        getSharedPreferences(
            "epub_reader",
            MODE_PRIVATE
        )
    }
    //private var darkMode by mutableStateOf(
    //     preferences.getBoolean("dark_mode", false)
    //  )

    private val epubPicker =
        registerForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->

            if (uri != null) {

                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (e: SecurityException) {
                    // Enkelte filkilder støtter ikke dette.
                }

                val uriString = uri.toString()

                preferences.edit()
                    .putString(
                        "last_epub_uri",
                        uriString
                    )
                    .apply()

                selectedEpubUri = uriString

                selectedEpubName =
                    uri.lastPathSegment ?: "Unknown fil"

                currentPage = 0
                bookText = ""
                isLoading = true
                errorMessage = null

                loadEpub(uri)
            }
        }

    private var selectedEpubUri by mutableStateOf<String?>(null)

    private var selectedEpubName by mutableStateOf<String?>(null)

    private var currentPage by mutableIntStateOf(0)

    private var bookText by mutableStateOf("")

    private var isLoading by mutableStateOf(false)

    private var errorMessage by mutableStateOf<String?>(null)

    private var bookSession: EpubParser.BookSession? = null

    private var currentChapter = 0

    private var showChapterList = false

    private var bookInfo by mutableStateOf<EpubParser.BookInfo?>(null)
    private var darkMode by mutableStateOf(false)

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.dark(
                android.graphics.Color.TRANSPARENT
            ),
            navigationBarStyle = androidx.activity.SystemBarStyle.dark(
                android.graphics.Color.TRANSPARENT
            )
        )



        // -------------------------------------------------
        // Retrieve last selected EPUB
        // -------------------------------------------------

        val savedUri =
            preferences.getString(
                "last_epub_uri",
                null
            )

        if (savedUri != null) {

            try {

                val uri =
                    Uri.parse(savedUri)

                contentResolver
                    .openInputStream(uri)
                    ?.use {

                        selectedEpubUri =
                            savedUri

                        selectedEpubName =
                            uri.lastPathSegment
                                ?: "Unknown fil"

                        currentPage =
                            preferences.getInt(
                                "page_$savedUri",
                                0
                            )

                        loadEpub(uri)
                    }

            } catch (e: Exception) {

                selectedEpubUri = null
                selectedEpubName = null
                currentPage = 0
            }
        }
        darkMode = preferences.getBoolean("dark_mode", false)

        window.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(
                if (darkMode) {
                    android.graphics.Color.rgb(18, 18, 18)
                } else {
                    android.graphics.Color.WHITE
                }
            )
        )
        setContent {

            EpubReaderTheme (
                darkTheme = darkMode
            ) {

                ReaderScreen(
                    currentPage = currentPage,
                    bookText = bookText,
                    selectedEpubName =
                        selectedEpubName,
                    isLoading = isLoading,
                    preferences = preferences,
                    errorMessage =
                        errorMessage,
                    darkMode =darkMode,

                    onDarkModeChanged = { enabled ->
                        darkMode = enabled
                        window.setBackgroundDrawable(
                            android.graphics.drawable.ColorDrawable(
                                if (enabled) {
                                    android.graphics.Color.rgb(18, 18, 18)
                                } else {
                                    android.graphics.Color.WHITE
                                }
                            )
                        )
                        preferences.edit()
                            .putBoolean("dark_mode", enabled)
                            .apply()

                    },

                    onOpenEpub = {

                        epubPicker.launch(
                            arrayOf(
                                "application/epub+zip",
                                "application/zip",
                                "application/octet-stream"
                            )
                        )
                    },

                    onPageChanged = { page: Int ->

                        currentPage = page

                        selectedEpubUri?.let { uri ->

                            preferences.edit()
                                .putInt(
                                    "page_$uri",
                                    page
                                )
                                .apply()
                        }
                    },
                    chapterTitles = bookSession?.bookInfo?.chapters
                        ?.map { it.title }
                        ?: emptyList(),

                    onChapterSelected = { index ->
                        preferences.edit()
                            .putInt("chapter_$selectedEpubUri", index)
                            .apply()

                        loadChapter(index)
                    }
                )
            }
        }
    }


    // =====================================================
    // READ EPUB FILE
    // =====================================================


    private fun loadEpub(uri: Uri) {
        isLoading = true
        errorMessage = null

        CoroutineScope(Dispatchers.IO).launch {
            var newSession: EpubParser.BookSession? = null

            try {
                val input = contentResolver.openInputStream(uri)
                    ?: throw Exception("Could not open EPUB-file.")

                newSession = EpubParser.openSession(input)

                val info = newSession.bookInfo

                if (info.chapters.isEmpty()) {
                    throw Exception("No chapter in EPUB-fil.")
                }

                // Retrieve last read chapter EPUB-fil
                val savedChapter = preferences.getInt(
                    "chapter_$uri",
                    0
                ).coerceIn(0, info.chapters.lastIndex)

                // Fetch saved chapter
                val chapterText = newSession.loadChapter(savedChapter)

                // Fetch last read page for this EPUB-file
                val savedPage = preferences.getInt(
                    "page_$uri",
                    0
                )

                withContext(Dispatchers.Main) {
                    bookSession?.close()
                    bookSession = newSession

                    currentChapter = savedChapter
                    bookText = chapterText
                    currentPage = savedPage
                    isLoading = false

                    errorMessage = if (chapterText.isBlank()) {
                        "No text in this chapter."
                    } else {
                        null
                    }
                }
            } catch (e: Exception) {
                newSession?.close()

                withContext(Dispatchers.Main) {
                    bookText = ""
                    currentPage = 0
                    isLoading = false
                    errorMessage =
                        "Could not read  EPUB-file:\n${e.message}"
                }
            }
        }
    }
    private fun loadChapter(chapterIndex: Int) {
        val session = bookSession ?: return
        val chapters = session.bookInfo.chapters

        if (chapterIndex !in chapters.indices) return

        isLoading = true
        errorMessage = null

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val chapterText = session.loadChapter(chapterIndex)

                withContext(Dispatchers.Main) {
                    currentChapter = chapterIndex
                    currentPage = 0
                    bookText = chapterText
                    isLoading = false

                    errorMessage = if (chapterText.isBlank()) {
                        "No text in this chapter."
                    } else {
                        null
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isLoading = false
                    errorMessage =
                        "Could not read chapter:\n${e.message}"
                }
            }
        }
    }
}





// =========================================================
// READER SCREEN
// =========================================================

@Composable
fun ReaderScreen(

    currentPage: Int,
    bookText: String,
    selectedEpubName: String?,
    isLoading: Boolean,
    errorMessage: String?,
    onOpenEpub: () -> Unit,
    preferences: android.content.SharedPreferences,
    onPageChanged: (Int) -> Unit,
    darkMode: Boolean,
    onDarkModeChanged: (Boolean) -> Unit,
    chapterTitles: List<String>,
    onChapterSelected: (Int) -> Unit
) {

    var totalDrag by remember {
        mutableFloatStateOf(0f)
    }



    // -----------------------------------------------------
    // TextMeasurer
    // -----------------------------------------------------

    val textMeasurer =
        rememberTextMeasurer()

    var fontSize by rememberSaveable {
        mutableIntStateOf(
            preferences.getInt("font_size", 20)
        )
    }
    val textStyle =
        TextStyle(
            fontFamily = FontFamily.Serif,
            fontSize = fontSize.sp,
            lineHeight = (fontSize * 1.45f).sp
        )

    // -----------------------------------------------------
    // Here the actual size for reading area is saved
    // -----------------------------------------------------

    var readerWidth by remember {
        mutableIntStateOf(0)
    }

    var readerHeight by remember {
        mutableIntStateOf(0)
    }

    // -----------------------------------------------------
    // Pages that is calculated from screen size
    // -----------------------------------------------------

    var pages by remember {
        mutableStateOf<List<String>>(
            emptyList()
        )
    }

    var showChapterList by remember { mutableStateOf(false) }


    // -----------------------------------------------------
    // Create page when:
    //
    // - EPUB-text changes
    // - reading area change size
    // -----------------------------------------------------

    LaunchedEffect(bookText, readerWidth, readerHeight, fontSize) {
        if (bookText.isBlank() || readerWidth <= 0 || readerHeight <= 0) {
            pages = emptyList()
            return@LaunchedEffect
        }

        pages = paginateText(
            text = bookText,
            width = readerWidth,
            height = readerHeight,
            textMeasurer = textMeasurer,
            textStyle = textStyle
        )
    }



    // -----------------------------------------------------
    // Ensure that the stored page remains valid after re-pagination.
    // -----------------------------------------------------

    val safePage =
        if (pages.isEmpty()) {
            0
        } else {
            currentPage.coerceIn(
                0,
                pages.lastIndex
            )
        }


    Box(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(
                start = 32.dp,
                end = 32.dp,
                top = 16.dp,
                bottom = 16.dp
            )
    ) {

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    if (darkMode) Color(0xFF121212)
                    else Color.White
                )
        ) {

            // -------------------------------------------------
            // Button
            //
            // No swipe detection here.
            // -------------------------------------------------

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onOpenEpub,
                    contentPadding = PaddingValues(
                        horizontal = 6.dp,
                        vertical = 4.dp
                    )
                ) {
                    Text("Open")
                }

                Button(
                    onClick = {
                        showChapterList = !showChapterList

                    },
                    contentPadding = PaddingValues(
                        horizontal = 6.dp,
                        vertical = 4.dp
                    )
                ) {
                    Text("Chapters")
                }

                Button(
                    onClick = {
                        onDarkModeChanged(!darkMode)

                    },
                    contentPadding = PaddingValues(
                        horizontal = 6.dp,
                        vertical = 4.dp
                    )
                ) {
                    Text(
                        if (darkMode) "☀" else "🌙"
                    )
                }
                Button(
                    onClick = {
                        if (fontSize > 12) {
                            fontSize -= 1
                            preferences.edit()
                                .putInt("font_size", fontSize)
                                .apply()
                        }

                    },
                    contentPadding = PaddingValues(
                        horizontal = 6.dp,
                        vertical = 4.dp
                    )
                ) {
                    Text("−")
                }
                Button(
                    onClick = {
                        if (fontSize < 32) {
                            fontSize += 1
                            preferences.edit()
                                .putInt("font_size", fontSize)
                                .apply()

                        }
                    },
                    contentPadding = PaddingValues(
                        horizontal = 6.dp,
                        vertical = 4.dp
                    )
                ) {
                    Text("+")
                }
            }


            if (selectedEpubName != null) {

                Text(
                    text =
                        "Selected file: $selectedEpubName",
                    color = if (darkMode) Color.White else Color.Black,

                    modifier =
                        Modifier.padding(
                            top = 12.dp
                        ),

                    fontSize = 16.sp
                )
            }
            if (showChapterList) {
                Button(
                    onClick = {
                        showChapterList = false
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp)
                ) {
                    Text("Back to reading")
                }
            }
            if (showChapterList && chapterTitles.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(8.dp)
                ) {
                    chapterTitles.forEachIndexed { index, title ->
                        TextButton(
                            onClick = {
                                onChapterSelected(index)
                                showChapterList = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "${index + 1}. $title",
                                color = if (darkMode) Color.White else Color.Black,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }


            // -------------------------------------------------
            // READING AREA
            // -------------------------------------------------

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .onSizeChanged { size ->

                        readerWidth =
                            size.width

                        readerHeight =
                            size.height
                    }
                    .pointerInput(
                        safePage,
                        pages.size
                    ) {

                        detectHorizontalDragGestures(

                            onDragStart = {
                                totalDrag = 0f
                            },

                            onHorizontalDrag = {
                                    change,
                                    dragAmount ->

                                change.consume()

                                totalDrag +=
                                    dragAmount
                            },

                            onDragEnd = {

                                val swipeThreshold =
                                    100f


                                if (
                                    totalDrag <
                                    -swipeThreshold
                                ) {

                                    // Left =
                                    // next page

                                    if (
                                        safePage <
                                        pages.lastIndex
                                    ) {

                                        onPageChanged(
                                            safePage + 1
                                        )
                                    }

                                } else if (
                                    totalDrag >
                                    swipeThreshold
                                ) {

                                    // Right =
                                    // Previous page

                                    if (
                                        safePage > 0
                                    ) {

                                        onPageChanged(
                                            safePage - 1
                                        )
                                    }
                                }


                                totalDrag = 0f
                            },

                            onDragCancel = {

                                totalDrag = 0f
                            }
                        )
                    }
            ) {

                when {

                    isLoading -> {

                        Text(
                            text =
                                "Loading EPUB ...",

                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(
                                        top = 20.dp
                                    ),

                            textAlign =
                                TextAlign.Center
                        )
                    }


                    errorMessage != null -> {

                        Text(
                            text =
                                errorMessage,

                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(
                                        top = 20.dp
                                    ),

                            fontSize = 16.sp
                        )
                    }


                    pages.isEmpty() -> {

                        if (
                            bookText.isBlank()
                        ) {

                            Text(
                                text =
                                    "Select an EPUB-file for start reading.",

                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(
                                            top = 20.dp
                                        ),

                                fontSize = 18.sp
                            )
                        }
                    }


                    else -> {

                        Text(
                            text =
                                pages[safePage],

                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(
                                        top = 20.dp
                                    ),

                            style =
                                textStyle,
                            color = if (darkMode) Color.White else Color.Black
                        )
                    }
                }
            }
        }


        // -----------------------------------------------------
        // PAGE NUMBER
        // -----------------------------------------------------


        if (pages.isNotEmpty()) {

            Text(
                text =
                    "${safePage + 1} / ${pages.size}",
                color = if (darkMode) Color.White else Color.Black,

                modifier =
                    Modifier
                        .align(
                            Alignment.BottomCenter
                        )
                        .fillMaxWidth(),

                textAlign =
                    TextAlign.Center,

                fontSize = 14.sp
            )
        }
    }
}


// =========================================================
// Screen based pagination
// =========================================================

private fun paginateText(
    text: String,
    width: Int,
    height: Int,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
    textStyle: TextStyle
): List<String> {

    if (text.isBlank() || width <= 0 || height <= 0) {
        return emptyList()
    }

    // Reserve some space at the bottom for page numbers.

    val availableHeight =
        (height - 100).coerceAtLeast(1)

    val constraints = Constraints(
        minWidth = 0,
        maxWidth = width,
        minHeight = 0,
        maxHeight = availableHeight
    )

    val result = mutableListOf<String>()

    var start = 0

    while (start < text.length) {

        // Try the rest of the text first.
        var low = start + 1
        var high = text.length
        var bestEnd = start + 1

        while (low <= high) {
            val mid = (low + high) / 2

            val candidate = text.substring(start, mid)

            val measured = textMeasurer.measure(
                text = candidate,
                style = textStyle,
                constraints = constraints
            )

            if (measured.hasVisualOverflow) {
                high = mid - 1
            } else {
                bestEnd = mid
                low = mid + 1
            }
        }

        // Make sure we don't stop in the middle of a word.
        var end = bestEnd

        if (end < text.length) {
            val lastSpace = text.lastIndexOf(' ', end - 1)

            if (lastSpace > start) {
                end = lastSpace
            }
        }

        // Safety check to ensure we can always proceed.
        if (end <= start) {
            end = minOf(start + 1, text.length)
        }

        result.add(
            text.substring(start, end).trim()
        )

        start = end

        // Skip whitespace after a page break.
        while (start < text.length && text[start].isWhitespace()) {
            start++
        }
    }

    return result
}

// =========================================================
// Find natural word break
// =========================================================

private fun findWordBoundary(
    text: String,
    start: Int,
    proposedEnd: Int
): Int {

    val end =
        proposedEnd.coerceAtMost(
            text.length
        )

    if (
        end >= text.length
    ) {
        return text.length
    }

    // -----------------------------------------------------
    // If we are already positioned at a space, this is
    // a natural separator.
    // -----------------------------------------------------

    if (
        text[end - 1].isWhitespace()
    ) {
        return end
    }

    // -----------------------------------------------------
    // Find the last space before the suggested end.
    // -----------------------------------------------------

    val lastSpace =
        text.lastIndexOf(
            ' ',
            end - 1
        )

    val lastNewline =
        text.lastIndexOf(
            '\n',
            end - 1
        )

    return maxOf(
        lastSpace,
        lastNewline
    ).let { boundary ->

        if (
            boundary >= start
        ) {
            boundary + 1
        } else {
            end
        }
    }
}


// =========================================================
// Emergency exit for extremely long words
// =========================================================

private fun findForcedBreak(
    text: String,
    start: Int,
    width: Int,
    height: Int,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
    textStyle: TextStyle
): Int {

    var end =
        (start + 1).coerceAtMost(
            text.length
        )

    val constraints =
        Constraints(
            minWidth = 0,
            maxWidth = width,
            minHeight = 0,
            maxHeight = height
        )

    while (
        end < text.length
    ) {

        val candidate =
            text.substring(
                start,
                end
            )

        val result =
            textMeasurer.measure(
                text = AnnotatedString(
                    candidate
                ),
                style = textStyle,
                constraints = constraints,
                skipCache = true
            )

        if (
            result.didOverflowHeight
        ) {
            break
        }

        end++
    }

    return maxOf(
        start + 1,
        end - 1
    )
}


// =========================================================
// EPUB PARSER
// =========================================================

object EpubParser {

    data class BookInfo(
        val title: String = "",
        val author: String = "",
        val category: String = "",
        val description: String = "",
        val chapters: List<ChapterInfo> = emptyList()
    )

    data class ChapterInfo(
        val id: String,
        val title: String,
        val href: String
    )



    class BookSession internal constructor(
        private val tempFile: File,
        private val zip: ZipFile,
        private val opfDirectory: String,
        private val spine: List<ManifestItem>,
        val bookInfo: BookInfo
    ) : java.io.Closeable {

        fun loadChapter(chapterIndex: Int): String {
            require(chapterIndex in spine.indices) {
                "Invalid chapter number: $chapterIndex"
            }

            val item = spine[chapterIndex]
            android.util.Log.d(
                "EpubReader",
                "Chapter ${chapterIndex + 1}: id=${item.id}, href=${item.href}"
            )
            val path = if (opfDirectory.isEmpty()) {
                item.href
            } else {
                "$opfDirectory/${item.href}"
            }

            val normalizedPath = EpubParser.normalizePath(path)
            val entry = zip.getEntry(normalizedPath)
                ?: throw Exception("Did not find chapter file: $normalizedPath")

            val chapterText = zip.getInputStream(entry).use {
                EpubParser.readXhtml(it)
            }

            val title = bookInfo.chapters[chapterIndex].title

            return if (
                title.isNotBlank() &&
                chapterText.startsWith("$title $title")
            ) {
                chapterText.removePrefix("$title ")
            } else {
                chapterText
            }
        }

        override fun close() {
            try {
                zip.close()
            } finally {
                tempFile.delete()
            }
        }
    }



    private data class EpubStructure(
        val opfPath: String,
        val opfDirectory: String,
        val manifest: Map<String, ManifestItem>,
        val spine: List<ManifestItem>
    )


    fun openSession(input: InputStream): BookSession {
        val tempFile = File.createTempFile("epub_session_", ".epub")
        var openedZip: ZipFile? = null

        try {
            input.use { source ->
                tempFile.outputStream().use { target ->
                    source.copyTo(target)
                }
            }

            val zip = ZipFile(tempFile)
            openedZip = zip

            val containerEntry = zip.getEntry("META-INF/container.xml")
                ?: throw Exception("Could not find META-INF/container.xml")

            val rootFile = zip.getInputStream(containerEntry).use {
                readContainer(it)
            }

            val opfDirectory = rootFile.substringBeforeLast("/", "")
            val opfEntry = zip.getEntry(rootFile)
                ?: throw Exception("Could not find content.opf")

            var title = ""
            var author = ""
            var category = ""
            var description = ""

            zip.getInputStream(opfEntry).use { opfInput ->
                val parser = Xml.newPullParser()
                parser.setInput(opfInput, null)

                while (parser.next() != XmlPullParser.END_DOCUMENT) {
                    if (parser.eventType == XmlPullParser.START_TAG) {
                        when (parser.name) {
                            "title" -> title = parser.nextText().trim()
                            "creator" -> author = parser.nextText().trim()
                            "subject" -> category = parser.nextText().trim()
                            "description" -> description = parser.nextText().trim()
                        }
                    }
                }
            }

            val spine = zip.getInputStream(opfEntry).use {
                readOpf(it)
            }

            val chapters = spine.mapIndexed { index, item ->
                val path = if (opfDirectory.isEmpty()) {
                    item.href
                } else {
                    "$opfDirectory/${item.href}"
                }

                val normalizedPath = normalizePath(path)
                val entry = zip.getEntry(normalizedPath)

                val chapterTitle = if (entry == null) {
                    ""
                } else {
                    zip.getInputStream(entry).use { chapterInput ->
                        val parser = Xml.newPullParser()
                        parser.setInput(chapterInput, null)

                        var foundTitle = ""

                        while (parser.next() != XmlPullParser.END_DOCUMENT) {
                            if (
                                parser.eventType == XmlPullParser.START_TAG &&
                                parser.name.equals("title", ignoreCase = true)
                            ) {
                                foundTitle = parser.nextText().trim()
                                if (foundTitle.isNotBlank()) break
                            }
                        }

                        foundTitle
                    }
                }

                ChapterInfo(
                    id = item.id,
                    title = chapterTitle.ifBlank {
                        "Chapter ${index + 1}"
                    },
                    href = item.href
                )
            }
            android.util.Log.d(
                "EpubReader",
                "Number of chapters: ${chapters.size}"
            )
            val bookInfo = BookInfo(
                title = title,
                author = author,
                category = category,
                description = description,
                chapters = chapters
            )

            return BookSession(
                tempFile = tempFile,
                zip = zip,
                opfDirectory = opfDirectory,
                spine = spine,
                bookInfo = bookInfo
            )
        } catch (e: Exception) {
            try {
                openedZip?.close()
            } finally {
                tempFile.delete()
            }
            throw e
        }
    }

    fun parse(
        input: InputStream
    ): String {

        val tempFile =
            File.createTempFile(
                "epub_",
                ".epub"
            )

        try {

            input.use { source ->

                tempFile
                    .outputStream()
                    .use { target ->

                        source.copyTo(
                            target
                        )
                    }
            }


            ZipFile(tempFile).use { zip ->

                // -------------------------------------------------
                // Find container.xml
                // -------------------------------------------------

                val containerEntry =
                    zip.getEntry(
                        "META-INF/container.xml"
                    )
                        ?: throw Exception(
                            "Could not find META-INF/container.xml"
                        )


                val rootFile =
                    readContainer(
                        zip.getInputStream(
                            containerEntry
                        )
                    )


                // -------------------------------------------------
                // Find content.opf
                // -------------------------------------------------

                val opfDirectory =
                    rootFile.substringBeforeLast(
                        "/",
                        ""
                    )


                val opfEntry =
                    zip.getEntry(rootFile)
                        ?: throw Exception(
                            "Could not find content.opf"
                        )


                val spine =
                    readOpf(
                        zip.getInputStream(
                            opfEntry
                        )
                    )


                // -------------------------------------------------
                // Read the chapters in the correct order
                // -------------------------------------------------

                val allText =
                    StringBuilder()


                for (item in spine) {

                    val path =
                        if (
                            opfDirectory.isEmpty()
                        ) {
                            item.href
                        } else {
                            "$opfDirectory/${item.href}"
                        }


                    val normalizedPath =
                        normalizePath(path)


                    val entry =
                        zip.getEntry(
                            normalizedPath
                        )
                            ?: continue


                    val text =
                        readXhtml(
                            zip.getInputStream(
                                entry
                            )
                        )


                    if (
                        text.isNotBlank()
                    ) {

                        if (
                            allText.isNotEmpty()
                        ) {

                            allText.append(
                                "\n\n"
                            )
                        }

                        allText.append(
                            text
                        )
                    }
                }


                return allText
                    .toString()
                    .trim()
            }

        } finally {

            tempFile.delete()
        }
    }

    fun readBookInfo(input: InputStream): BookInfo {
        val tempFile = File.createTempFile("epub_info_", ".epub")

        try {
            input.use { source ->
                tempFile.outputStream().use { target ->
                    source.copyTo(target)
                }
            }

            ZipFile(tempFile).use { zip ->
                val containerEntry = zip.getEntry("META-INF/container.xml")
                    ?: throw Exception("Could not find META-INF/container.xml")

                val rootFile = readContainer(zip.getInputStream(containerEntry))
                val opfDirectory = rootFile.substringBeforeLast("/", "")
                val opfEntry = zip.getEntry(rootFile)
                    ?: throw Exception("Could not find content.opf")

                val parser = Xml.newPullParser()
                zip.getInputStream(opfEntry).use { opfInput ->
                    parser.setInput(opfInput, null)

                    var title = ""
                    var author = ""
                    var category = ""
                    var description = ""

                    while (parser.next() != XmlPullParser.END_DOCUMENT) {
                        if (parser.eventType == XmlPullParser.START_TAG) {
                            when (parser.name) {
                                "title" -> title = parser.nextText().trim()
                                "creator" -> author = parser.nextText().trim()
                                "subject" -> category = parser.nextText().trim()
                                "description" -> description = parser.nextText().trim()
                            }
                        }
                    }

                    val spine = zip.getInputStream(opfEntry).use { readOpf(it) }

                    val chapters = spine.mapIndexedNotNull { index, item ->
                        val path = if (opfDirectory.isEmpty()) {
                            item.href
                        } else {
                            "$opfDirectory/${item.href}"
                        }

                        val normalizedPath = normalizePath(path)
                        val entry = zip.getEntry(normalizedPath) ?: return@mapIndexedNotNull null

                        val chapterTitle = zip.getInputStream(entry).use { chapterInput ->
                            val chapterParser = Xml.newPullParser()
                            chapterParser.setInput(chapterInput, null)

                            var foundTitle = ""
                            while (chapterParser.next() != XmlPullParser.END_DOCUMENT) {
                                if (chapterParser.eventType == XmlPullParser.START_TAG &&
                                    chapterParser.name.equals("title", ignoreCase = true)
                                ) {
                                    foundTitle = chapterParser.nextText().trim()
                                    if (foundTitle.isNotBlank()) break
                                }
                            }
                            foundTitle
                        }

                        ChapterInfo(
                            id = item.id,
                            title = chapterTitle.ifBlank { "Chapter ${index + 1}" },
                            href = item.href
                        )
                    }

                    return BookInfo(
                        title = title,
                        author = author,
                        category = category,
                        description = description,
                        chapters = chapters
                    )
                }
            }
        } finally {
            tempFile.delete()
        }
    }


    fun loadChapter(input: InputStream, chapterIndex: Int): String {
        require(chapterIndex >= 0) {
            "Chapter number not valid: $chapterIndex"
        }

        val tempFile = File.createTempFile("epub_chapter_", ".epub")

        try {
            input.use { source ->
                tempFile.outputStream().use { target ->
                    source.copyTo(target)
                }
            }

            ZipFile(tempFile).use { zip ->
                val containerEntry = zip.getEntry("META-INF/container.xml")
                    ?: throw Exception("Could not find META-INF/container.xml")

                val rootFile = zip.getInputStream(containerEntry).use {
                    readContainer(it)
                }

                val opfDirectory = rootFile.substringBeforeLast("/", "")
                val opfEntry = zip.getEntry(rootFile)
                    ?: throw Exception("Could not find content.opf")

                val spine = zip.getInputStream(opfEntry).use {
                    readOpf(it)
                }

                if (chapterIndex !in spine.indices) {
                    throw Exception("Chapter number does not exist: $chapterIndex")
                }

                val item = spine[chapterIndex]

                val path = if (opfDirectory.isEmpty()) {
                    item.href
                } else {
                    "$opfDirectory/${item.href}"
                }

                val normalizedPath = normalizePath(path)
                val chapterEntry = zip.getEntry(normalizedPath)
                    ?: throw Exception("Could not find the chapter file: $normalizedPath")

                return zip.getInputStream(chapterEntry).use {
                    readXhtml(it)
                }
            }
        } finally {
            tempFile.delete()
        }
    }


    // =====================================================
    // container.xml
    // =====================================================

    private fun readContainer(
        input: InputStream
    ): String {

        val parser =
            Xml.newPullParser()

        parser.setInput(
            input,
            null
        )


        while (
            parser.next() !=
            XmlPullParser.END_DOCUMENT
        ) {

            if (
                parser.eventType ==
                XmlPullParser.START_TAG &&
                parser.name == "rootfile"
            ) {

                return parser.getAttributeValue(
                    null,
                    "full-path"
                )
                    ?: throw Exception(
                        "Could not find a path to content.opf"
                    )
            }
        }


        throw Exception(
            "Found no root file in container.xml"
        )
    }


    // =====================================================
    // content.opf
    // =====================================================

    internal data class ManifestItem(
        val id: String,
        val href: String,
        val mediaType: String
    )


    private fun readOpf(
        input: InputStream
    ): List<ManifestItem> {

        val parser =
            Xml.newPullParser()

        parser.setInput(
            input,
            null
        )


        val manifest =
            mutableMapOf<
                    String,
                    ManifestItem
                    >()


        val spine =
            mutableListOf<String>()


        while (
            parser.next() !=
            XmlPullParser.END_DOCUMENT
        ) {

            if (
                parser.eventType ==
                XmlPullParser.START_TAG
            ) {

                when (
                    parser.name
                ) {

                    "item" -> {

                        val id =
                            parser.getAttributeValue(
                                null,
                                "id"
                            )

                        val href =
                            parser.getAttributeValue(
                                null,
                                "href"
                            )

                        val mediaType =
                            parser.getAttributeValue(
                                null,
                                "media-type"
                            )


                        if (
                            id != null &&
                            href != null &&
                            mediaType != null
                        ) {

                            manifest[id] =
                                ManifestItem(
                                    id = id,
                                    href = href,
                                    mediaType =
                                        mediaType
                                )
                        }
                    }


                    "itemref" -> {

                        val idref =
                            parser.getAttributeValue(
                                null,
                                "idref"
                            )


                        if (
                            idref != null
                        ) {

                            spine.add(
                                idref
                            )
                        }
                    }
                }
            }
        }


        return spine.mapNotNull { id ->

            val item =
                manifest[id]


            if (
                item != null &&
                (
                        item.mediaType == "application/xhtml+xml" ||
                                item.mediaType == "text/html"
                        ) &&
                !item.href.substringAfterLast("/").equals(
                    "nav.xhtml",
                    ignoreCase = true
                )
            ) {
                item
            } else {
                null
            }
        }
    }


    // =====================================================
    // XHTML / HTML
    // =====================================================

    private fun readXhtml(
        input: InputStream
    ): String {

        val parser =
            Xml.newPullParser()

        parser.setInput(
            input,
            null
        )


        val result =
            StringBuilder()


        while (
            parser.next() !=
            XmlPullParser.END_DOCUMENT
        ) {

            when (
                parser.eventType
            ) {

                XmlPullParser.TEXT -> {

                    val text =
                        parser.text
                            ?.replace(
                                Regex("\\s+"),
                                " "
                            )
                            ?.trim()


                    if (
                        !text.isNullOrBlank()
                    ) {

                        if (
                            result.isNotEmpty() &&
                            !result.endsWith(" ")
                        ) {

                            result.append(
                                " "
                            )
                        }

                        result.append(
                            text
                        )
                    }
                }


                XmlPullParser.END_TAG -> {

                    when (
                        parser.name.lowercase()
                    ) {

                        "br" -> {
                            result.append(" ")
                        }

                        "p",
                        "div",
                        "h1",
                        "h2",
                        "h3",
                        "h4",
                        "h5",
                        "h6",
                        "li" -> {

                            if (
                                result.isNotEmpty() &&
                                !result.endsWith(
                                    "\n"
                                )
                            ) {

                                result.append(
                                    "\n"
                                )
                            }
                        }
                    }
                }
            }
        }


        return result
            .toString()
            .replace(
                Regex("\n{3,}"),
                "\n\n"
            )
            .trim()
    }


    // =====================================================
    // Normalize file path
    // =====================================================

    private fun normalizePath(
        path: String
    ): String {

        val parts =
            mutableListOf<String>()


        for (
        part in path.split("/")
        ) {

            when (part) {

                "",
                "." -> {
                    // Ignorer
                }


                ".." -> {

                    if (
                        parts.isNotEmpty()
                    ) {

                        parts.removeAt(
                            parts.lastIndex
                        )
                    }
                }


                else -> {

                    parts.add(
                        part
                    )
                }
            }
        }


        return parts.joinToString(
            "/"
        )
    }
}
