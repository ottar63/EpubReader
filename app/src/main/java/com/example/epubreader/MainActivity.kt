package com.example.epubreader

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
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
// import androidx.compose.runtime.mutableStateOf



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
                    uri.lastPathSegment ?: "Ukjent fil"

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
        // Hent sist valgte EPUB
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
                                ?: "Ukjent fil"

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
                    }
                )
            }
        }
    }


    // =====================================================
    // LAST INN EPUB
    // =====================================================

    private fun loadEpub(uri: Uri) {

        CoroutineScope(
            Dispatchers.IO
        ).launch {

            try {

                val loadedText =
                    contentResolver
                        .openInputStream(uri)
                        ?.use { input ->

                            EpubParser.parse(
                                input
                            )

                        }
                        ?: throw Exception(
                            "Kunne ikke åpne EPUB-filen."
                        )

                withContext(
                    Dispatchers.Main
                ) {

                    bookText = loadedText

                    isLoading = false

                    errorMessage =
                        if (loadedText.isBlank()) {
                            "Fant ingen tekst i EPUB-filen."
                        } else {
                            null
                        }
                }

            } catch (e: Exception) {

                withContext(
                    Dispatchers.Main
                ) {

                    bookText = ""

                    currentPage = 0

                    isLoading = false

                    errorMessage =
                        "Kunne ikke lese EPUB-filen:\n${e.message}"
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
    onDarkModeChanged: (Boolean) -> Unit
) {

    var totalDrag by remember {
        mutableFloatStateOf(0f)
    }



    // -----------------------------------------------------
    // Tekstmåler
    // -----------------------------------------------------

    val textMeasurer =
        rememberTextMeasurer()

    var fontSize by rememberSaveable {
        mutableStateOf(
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
    // Her lagres størrelsen på det faktiske leseområdet.
    // -----------------------------------------------------

    var readerWidth by remember {
        mutableIntStateOf(0)
    }

    var readerHeight by remember {
        mutableIntStateOf(0)
    }

    // -----------------------------------------------------
    // Sider som er beregnet ut fra skjermstørrelsen.
    // -----------------------------------------------------

    var pages by remember {
        mutableStateOf<List<String>>(
            emptyList()
        )
    }

    // -----------------------------------------------------
    // Lag sidene når:
    //
    // - EPUB-teksten endres
    // - leseområdet endrer størrelse
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
    // Pass på at lagret side er gyldig etter ny paginering.
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
            // KNAPP
            //
            // Ingen swipe-deteksjon her.
            // -------------------------------------------------

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onOpenEpub
                ) {
                    Text("Åpne EPUB")
                }

                Button(
                    onClick = {
                        onDarkModeChanged(!darkMode)
                    }
                ) {
                    Text(
                        if (darkMode) "☀ Lys" else "🌙 Mørk"
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
                    }
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
                    }
                ) {
                    Text("+")
                }
            }


            if (selectedEpubName != null) {

                Text(
                    text =
                        "Valgt fil: $selectedEpubName",

                    modifier =
                        Modifier.padding(
                            top = 12.dp
                        ),

                    fontSize = 16.sp
                )
            }


            // -------------------------------------------------
            // LESEOMRÅDE
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

                                    // Venstre =
                                    // neste side

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

                                    // Høyre =
                                    // forrige side

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
                                "Laster EPUB ...",

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
                                    "Velg en EPUB-fil for å begynne å lese.",

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
        // SIDETALL
        // -----------------------------------------------------
        //Text(
        //    text = "TEST: ${bookText.length} tegn, ${pages.size} sider",
        //    modifier = Modifier.padding(8.dp)
        //)

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
// SKJERMBASERT PAGINERING
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

    // Litt plass reserveres nederst til sidetallet.

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

        // Prøv resten av teksten først.
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

        // Pass på at vi ikke stopper midt i et ord.
        var end = bestEnd

        if (end < text.length) {
            val lastSpace = text.lastIndexOf(' ', end - 1)

            if (lastSpace > start) {
                end = lastSpace
            }
        }

        // Sikkerhetskontroll slik at vi alltid kommer videre.
        if (end <= start) {
            end = minOf(start + 1, text.length)
        }

        result.add(
            text.substring(start, end).trim()
        )

        start = end

        // Hopp over mellomrom etter sideskift.
        while (start < text.length && text[start].isWhitespace()) {
            start++
        }
    }

    return result
}

// =========================================================
// Finn naturlig ordskille
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
    // Hvis vi allerede står på et mellomrom, er dette
    // et naturlig skille.
    // -----------------------------------------------------

    if (
        text[end - 1].isWhitespace()
    ) {
        return end
    }

    // -----------------------------------------------------
    // Finn siste mellomrom før foreslått slutt.
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
// Nødutgang for ekstremt lange ord
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
                // Finn container.xml
                // -------------------------------------------------

                val containerEntry =
                    zip.getEntry(
                        "META-INF/container.xml"
                    )
                        ?: throw Exception(
                            "Fant ikke META-INF/container.xml"
                        )


                val rootFile =
                    readContainer(
                        zip.getInputStream(
                            containerEntry
                        )
                    )


                // -------------------------------------------------
                // Finn content.opf
                // -------------------------------------------------

                val opfDirectory =
                    rootFile.substringBeforeLast(
                        "/",
                        ""
                    )


                val opfEntry =
                    zip.getEntry(rootFile)
                        ?: throw Exception(
                            "Fant ikke content.opf"
                        )


                val spine =
                    readOpf(
                        zip.getInputStream(
                            opfEntry
                        )
                    )


                // -------------------------------------------------
                // Les kapitlene i riktig rekkefølge
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
                        "Fant ikke sti til content.opf"
                    )
            }
        }


        throw Exception(
            "Fant ingen rootfile i container.xml"
        )
    }


    // =====================================================
    // content.opf
    // =====================================================

    private data class ManifestItem(
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
                        item.mediaType ==
                                "application/xhtml+xml" ||
                                item.mediaType ==
                                "text/html"
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
    // Normaliser filsti
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
